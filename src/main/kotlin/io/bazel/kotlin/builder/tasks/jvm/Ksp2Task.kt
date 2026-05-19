/*
 * Copyright 2025 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package io.bazel.kotlin.builder.tasks.jvm

import io.bazel.kotlin.builder.utils.ArgMap
import io.bazel.kotlin.builder.utils.ArgMaps
import io.bazel.kotlin.builder.utils.Flag
import io.bazel.worker.Status
import io.bazel.worker.Work
import io.bazel.worker.WorkerContext
import java.io.File
import java.io.FileOutputStream
import java.net.URLClassLoader
import java.util.concurrent.atomic.AtomicReference
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.regex.Pattern
import java.util.zip.ZipFile

/**
 * KSP2 worker task.
 *
 * Executes KSP2 symbol processing entirely within the worker:
 * 1. Stages source files to a temporary directory (for worker isolation)
 * 2. Unpacks srcjars to a temporary directory
 * 3. Runs KSP2 via the cached Ksp2Invoker
 * 4. Packages generated sources/classes into output JARs
 *
 * This is a separate command from the main Build command for cleaner separation.
 *
 * ## Classloader architecture
 *
 * KSP2 loads ~40k classes per action (~130MB Metaspace). Loading them afresh per action
 * in a single URLClassLoader caused the Metaspace to grow monotonically because the JVM
 * cannot unload classes as long as any strong reference keeps the classloader alive, and
 * IntelliJ's static infrastructure holds such references across actions.
 *
 * The fix is to split the classpath into two tiers, using separate Bazel flags:
 *
 *   sharedClassLoader (lives for the worker process lifetime)
 *     └─ --ksp2_core_classpath: ksp2_invoker.jar + symbol-processing-aa.jar + KSP2 API jars
 *        → the ~130 MB of IntelliJ/FIR/KSP2 infrastructure, same JARs for every target
 *
 *   kspClassLoader (per-action, closed after each action)
 *     └─ parent: sharedClassLoader
 *     └─ --processor_classpath: annotation-processor JARs + their transitive deps
 *        → variable per target, typically a few MB, trivially GC-eligible after close()
 */
class Ksp2Task : Work {
  // Shared classloader holding the heavy KSP2/IntelliJ core jars — created once on the first
  // action and reused for every subsequent action. Never closed.
  private val sharedClassLoader = AtomicReference<URLClassLoader?>(null)

  companion object {
    private val FLAGFILE_RE = Pattern.compile("""^--flagfile=((.*)-(\d+).params)$""").toRegex()

    enum class Ksp2Flags(
      override val flag: String,
    ) : Flag {
      MODULE_NAME("--module_name"),
      SOURCES("--sources"),
      SOURCE_JARS("--source_jars"),
      LIBRARIES("--libraries"),
      KSP2_CORE_CLASSPATH("--ksp2_core_classpath"),
      PROCESSOR_CLASSPATH("--processor_classpath"),
      GENERATED_SOURCES_OUTPUT("--generated_sources_output"),
      GENERATED_CLASSES_OUTPUT("--generated_classes_output"),
      LANGUAGE_VERSION("--language_version"),
      API_VERSION("--api_version"),
      JVM_TARGET("--jvm_target"),
      JDK_HOME("--jdk_home"),
      KSP_OPTIONS("--ksp_options"),
    }

    fun parseKspOptions(entries: List<String>): Map<String, String> =
      entries.associate { entry ->
        val eqIdx = entry.indexOf('=')
        if (eqIdx >= 0) entry.substring(0, eqIdx) to entry.substring(eqIdx + 1) else entry to ""
      }
  }

  // Returns the shared classloader, creating it from the core KSP2 JARs on the first call.
  // Thread-safe via CAS; subsequent calls return the already-created instance.
  private fun getOrCreateSharedClassLoader(coreUrls: Array<java.net.URL>): URLClassLoader {
    sharedClassLoader.get()?.let { return it }
    val newLoader = URLClassLoader(coreUrls, ClassLoader.getSystemClassLoader())
    return if (sharedClassLoader.compareAndSet(null, newLoader)) {
      newLoader
    } else {
      runCatching { newLoader.close() }
      sharedClassLoader.get()!!
    }
  }

  override fun invoke(
    ctx: WorkerContext.TaskContext,
    args: Iterable<String>,
  ): Status {
    val argsList = args.toList()
    check(argsList.isNotEmpty()) { "expected at least a single arg" }

    val lines =
      FLAGFILE_RE.matchEntire(argsList[0])?.groups?.get(1)?.let {
        Files.readAllLines(FileSystems.getDefault().getPath(it.value), StandardCharsets.UTF_8)
      } ?: argsList

    val argMap = ArgMaps.from(lines)

    val exitCode = execute(ctx, argMap)
    val nonHeap = java.lang.management.ManagementFactory.getMemoryMXBean().nonHeapMemoryUsage.used / 1024 / 1024
    System.err.println("[KSP2] action done nonheap=${nonHeap}MB")
    return if (exitCode == 0) Status.SUCCESS else Status.ERROR
  }

  private fun execute(
    taskContext: WorkerContext.TaskContext,
    argMap: ArgMap,
  ): Int {
    val workingDir = taskContext.directory
    val moduleName = argMap.mandatorySingle(Ksp2Flags.MODULE_NAME)

    // Create temporary directories for KSP2 processing
    val kspWorkDir = workingDir.resolve("_ksp2").resolve(moduleName)
    val stagedSourcesDir = kspWorkDir.resolve("staged_sources")
    val kotlinOutputDir = kspWorkDir.resolve("kotlin_out")
    val javaOutputDir = kspWorkDir.resolve("java_out")
    val classOutputDir = kspWorkDir.resolve("class_out")
    val resourceOutputDir = kspWorkDir.resolve("resource_out")
    val cachesDir = kspWorkDir.resolve("caches")

    listOf(
      stagedSourcesDir,
      kotlinOutputDir,
      javaOutputDir,
      classOutputDir,
      resourceOutputDir,
      cachesDir,
    ).forEach {
      Files.createDirectories(it)
    }

    try {
      // Stage source files to isolated directory
      val sourceRoots = mutableSetOf<String>()
      val javaSourceRoots = mutableSetOf<String>()

      // Stage individual source files
      val sources = argMap.optional(Ksp2Flags.SOURCES) ?: emptyList()
      for (source in sources) {
        val sourceFile = File(source)
        val targetFile = stagedSourcesDir.resolve(source).toFile()
        targetFile.parentFile?.mkdirs()
        sourceFile.copyTo(targetFile, overwrite = true)

        // Track source roots (directories containing sources)
        val sourceRoot =
          if (sourceFile.parentFile != null) {
            stagedSourcesDir.resolve(sourceFile.parentFile.path).toString()
          } else {
            stagedSourcesDir.toString()
          }
        sourceRoots.add(sourceRoot)
        if (source.endsWith(".java")) {
          javaSourceRoots.add(sourceRoot)
        }
      }

      // Unpack srcjars directly
      val srcjars = argMap.optional(Ksp2Flags.SOURCE_JARS) ?: emptyList()
      for (srcjar in srcjars) {
        ZipFile(srcjar).use { zip ->
          zip.entries().asSequence().forEach { entry ->
            if (!entry.isDirectory) {
              val targetFile = stagedSourcesDir.resolve(entry.name).toFile()
              targetFile.parentFile?.mkdirs()
              zip.getInputStream(entry).use { input ->
                targetFile.outputStream().use { output ->
                  input.copyTo(output)
                }
              }
              // Track source root for srcjar contents
              val parentDir = targetFile.parentFile?.path ?: stagedSourcesDir.toString()
              sourceRoots.add(parentDir)
              if (entry.name.endsWith(".java")) {
                javaSourceRoots.add(parentDir)
              }
            }
          }
        }
      }

      // If no sources, add a placeholder source root
      if (sourceRoots.isEmpty()) {
        sourceRoots.add(stagedSourcesDir.toString())
      }

      // Two-tier classloader setup:
      //
      //   --ksp2_core_classpath   → sharedClassLoader (lives for the worker lifetime)
      //     ksp2_invoker.jar + symbol-processing-aa.jar + KSP2 API jars + coroutines
      //     These are the same JARs for every KSP2 target; they carry ~130MB of
      //     IntelliJ/FIR classes that would OOM metaspace if reloaded per-action.
      //
      //   --processor_classpath   → kspClassLoader (per-action, closed after each action)
      //     Annotation-processor JARs + their transitive deps (Hilt, Room, Skabbard, …)
      //     Variable per target; typically a few MB, trivially GC-eligible after close().
      //
      // Disable JAR URL connection caching before creating classloaders. By default, Java's
      // JarURLConnection maintains a static fileCache that maps JAR paths to shared JarFile
      // instances. In a multiplex worker, concurrent actions load KSP2/processor JARs from
      // the same paths. When one action's URLClassLoader.close() closes its JarFile handle,
      // any concurrent action reading the same cached JarFile via getResourceAsStream() gets
      // "Stream closed" (observed as InvalidProtocolBufferException in FirFallbackBuiltinSymbolProvider
      // / ProtoBuf.PackageFragment.parseFrom). Setting false ensures each URLClassLoader
      // opens its own JarFile instance, not shared with or invalidated by concurrent actions.
      java.net.URLConnection.setDefaultUseCaches("jar", false)

      val coreClasspath = argMap.optional(Ksp2Flags.KSP2_CORE_CLASSPATH) ?: emptyList()
      val coreUrls = coreClasspath.map { File(it).toURI().toURL() }.toTypedArray()
      val sharedCl = getOrCreateSharedClassLoader(coreUrls)

      // Per-action classloader — holds only the processor JARs and their transitive deps,
      // parented to sharedCl so it can see all KSP2 classes. Closed after the action;
      // processor classes are trivially GC-eligible since they're not held by static state.
      // IMPORTANT: This classloader MUST be closed at the end of the action.
      val processorClasspath = argMap.optional(Ksp2Flags.PROCESSOR_CLASSPATH) ?: emptyList()
      val processorUrls = processorClasspath.map { File(it).toURI().toURL() }.toTypedArray()
      val kspClassLoader = URLClassLoader(processorUrls, sharedCl)

      try {
        val processorOptions = parseKspOptions(argMap.optional(Ksp2Flags.KSP_OPTIONS) ?: emptyList())

        // Load Ksp2Invoker via reflection. The class lives in the shared classloader (loaded
        // from ksp2_invoker.jar passed via --ksp2_core_classpath). The loadClass call delegates
        // through kspClassLoader → sharedCl where the class is found.
        val invokerClass = kspClassLoader.loadClass("io.bazel.kotlin.ksp2.Ksp2Invoker")
        val invoker =
          invokerClass
            .getConstructor(ClassLoader::class.java)
            .newInstance(kspClassLoader)
        val executeMethod =
          invokerClass.getMethod(
            "execute",
            String::class.java, // moduleName
            List::class.java, // sourceRoots
            List::class.java, // javaSourceRoots
            List::class.java, // libraries
            File::class.java, // kotlinOutputDir
            File::class.java, // javaOutputDir
            File::class.java, // classOutputDir
            File::class.java, // resourceOutputDir
            File::class.java, // cachesDir
            File::class.java, // projectBaseDir
            File::class.java, // outputBaseDir
            String::class.java, // jvmTarget
            String::class.java, // languageVersion
            String::class.java, // apiVersion
            File::class.java, // jdkHome
            Map::class.java, // processorOptions
            Int::class.java, // logLevel
            ClassLoader::class.java, // processorClassLoader
          )

        // Execute KSP2
        val code =
          executeMethod.invoke(
            invoker,
            moduleName,
            sourceRoots.map { File(it) },
            javaSourceRoots.map { File(it) },
            argMap.optional(Ksp2Flags.LIBRARIES)?.map { File(it) } ?: emptyList<File>(),
            kotlinOutputDir.toFile(),
            javaOutputDir.toFile(),
            classOutputDir.toFile(),
            resourceOutputDir.toFile(),
            cachesDir.toFile(),
            kspWorkDir.toFile(), // projectBaseDir
            kspWorkDir.toFile(), // outputBaseDir
            argMap.optionalSingle(Ksp2Flags.JVM_TARGET),
            argMap.optionalSingle(Ksp2Flags.LANGUAGE_VERSION),
            argMap.optionalSingle(Ksp2Flags.API_VERSION),
            argMap.optionalSingle(Ksp2Flags.JDK_HOME)?.let { File(it) },
            processorOptions,
            1, // logLevel
            kspClassLoader, // processorClassLoader
          ) as Int

        if (code != 0) {
          taskContext.error { "KSP2 failed with exit code: $code" }
          return code
        }
      } finally {
        try {
          kspClassLoader.close()
        } catch (_: Exception) {
          // Ignore — best-effort cleanup.
        }
      }

      // Package generated sources into srcjar
      val generatedSourcesOutput = argMap.mandatorySingle(Ksp2Flags.GENERATED_SOURCES_OUTPUT)
      packageDirectoriesToJar(
        outputPath = generatedSourcesOutput,
        directories = listOf(kotlinOutputDir, javaOutputDir),
      )

      // Package generated classes/resources into jar
      val generatedClassesOutput = argMap.mandatorySingle(Ksp2Flags.GENERATED_CLASSES_OUTPUT)
      packageDirectoriesToJar(
        outputPath = generatedClassesOutput,
        directories = listOf(classOutputDir, resourceOutputDir),
      )
      return 0
    } catch (e: Exception) {
      taskContext.error(e) { "KSP2 execution failed" }
      return 1
    } finally {
      // Clean up temporary directories
      try {
        kspWorkDir.toFile().deleteRecursively()
      } catch (_: Exception) {
        // Ignore cleanup errors
      }
    }
  }

  /**
   * Package files from directories into a JAR file.
   * Includes directory entries for compatibility with tools that expect them.
   */
  private fun packageDirectoriesToJar(
    outputPath: String,
    directories: List<Path>,
  ) {
    val manifest =
      Manifest().apply {
        mainAttributes.putValue("Manifest-Version", "1.0")
        mainAttributes.putValue("Created-By", "rules_kotlin KSP2")
      }

    JarOutputStream(FileOutputStream(outputPath), manifest).use { jar ->
      val addedEntries = mutableSetOf<String>()

      for (dir in directories) {
        if (!Files.exists(dir)) continue

        Files.walk(dir).use { stream ->
          stream.forEach { path ->
            val relativePath = dir.relativize(path).toString().replace('\\', '/')
            if (relativePath.isEmpty()) return@forEach

            if (Files.isDirectory(path)) {
              // Add directory entry (must end with /)
              val dirEntry = "$relativePath/"
              if (dirEntry !in addedEntries) {
                addedEntries.add(dirEntry)
                jar.putNextEntry(JarEntry(dirEntry))
                jar.closeEntry()
              }
            } else if (Files.isRegularFile(path)) {
              // Ensure parent directories are added first
              val parts = relativePath.split("/")
              var parentPath = ""
              for (i in 0 until parts.size - 1) {
                parentPath += parts[i] + "/"
                if (parentPath !in addedEntries) {
                  addedEntries.add(parentPath)
                  jar.putNextEntry(JarEntry(parentPath))
                  jar.closeEntry()
                }
              }

              // Add file entry
              if (relativePath !in addedEntries) {
                addedEntries.add(relativePath)
                jar.putNextEntry(JarEntry(relativePath))
                Files.copy(path, jar)
                jar.closeEntry()
              }
            }
          }
        }
      }
    }
  }
}
