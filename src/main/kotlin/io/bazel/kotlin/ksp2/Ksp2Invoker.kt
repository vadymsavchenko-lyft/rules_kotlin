/*
 * Copyright 2025 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.bazel.kotlin.ksp2

import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.KSPJvmConfig
import com.google.devtools.ksp.processing.KspGradleLogger
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import java.io.File
import java.util.ServiceLoader

/**
 * Wrapper for KSP2 invocation using direct API calls.
 *
 * This class is compiled against KSP2 classes (via neverlink_deps) and loaded
 * at runtime in a classloader that has the KSP2 jars. This follows the same
 * pattern as BuildToolsAPICompiler.
 */
@Suppress("unused")
class Ksp2Invoker(
  private val classLoader: ClassLoader,
) {
  /**
   * Execute KSP2 with the given configuration.
   *
   * @param logLevel Logger level (0=ERROR, 1=WARN, 2=INFO, 3=LOGGING)
   * @param processorClassLoader classloader to use for ServiceLoader discovery of
   *   SymbolProcessorProvider implementations. When using a two-tier classloader setup
   *   (shared core CL + per-action processor CL), pass the per-action classloader here
   *   so ServiceLoader scans only the annotation-processor JARs. Defaults to [classLoader].
   * @return Exit code (0 for success)
   */
  fun execute(
    moduleName: String,
    sourceRoots: List<File>,
    javaSourceRoots: List<File>,
    libraries: List<File>,
    kotlinOutputDir: File,
    javaOutputDir: File,
    classOutputDir: File,
    resourceOutputDir: File,
    cachesDir: File,
    projectBaseDir: File,
    outputBaseDir: File,
    jvmTarget: String?,
    languageVersion: String?,
    apiVersion: String?,
    jdkHome: File?,
    processorOptions: Map<String, String> = emptyMap(),
    logLevel: Int = 1,
    processorClassLoader: ClassLoader = classLoader,
  ): Int {
    // Load processors via ServiceLoader from the per-action classloader so only
    // annotation-processor JARs are scanned (not the full KSP2 core classpath).
    val processors =
      ServiceLoader.load(SymbolProcessorProvider::class.java, processorClassLoader).toList()

    // Build KSP2 configuration
    val kspConfig =
      KSPJvmConfig
        .Builder()
        .apply {
          this.moduleName = moduleName
          this.sourceRoots = sourceRoots
          this.javaSourceRoots = javaSourceRoots
          this.libraries = libraries
          this.kotlinOutputDir = kotlinOutputDir
          this.javaOutputDir = javaOutputDir
          this.classOutputDir = classOutputDir
          this.resourceOutputDir = resourceOutputDir
          this.cachesDir = cachesDir
          this.projectBaseDir = projectBaseDir
          this.outputBaseDir = outputBaseDir
          jvmTarget?.let { this.jvmTarget = it }
          languageVersion?.let { this.languageVersion = it }
          apiVersion?.let { this.apiVersion = it }
          jdkHome?.let { this.jdkHome = it }
          this.processorOptions = processorOptions
          this.mapAnnotationArgumentsInJava = true
        }.build()

    // Set the thread context classloader to this action's classloader before invoking KSP2.
    // IntelliJ's PluginXmlPathResolver resolves <xi:include> resources via
    // Thread.currentThread().contextClassLoader. In a persistent worker, without this
    // reset the context classloader stays pointing to the previous action's (now-closed)
    // URLClassLoader, causing "Stream closed" when the plugin XML loader tries to open
    // analysis-api-fir.xml / analysis-api-impl-base.xml on the second+ action.
    val previousContextCl = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = classLoader

    // Create logger and execute
    val logger = KspGradleLogger(logLevel)
    val ksp = KotlinSymbolProcessing(kspConfig, processors, logger)

    val result = try {
      ksp.execute().code
    } finally {
      Thread.currentThread().contextClassLoader = previousContextCl
    }
    cleanupLeaks()
    return result
  }

  // Removes all strong references from live JVM threads to classes loaded by kspClassLoader,
  // so the classloader becomes GC-eligible after close() and Metaspace can be reclaimed.
  //
  // Roots cleared across ALL live threads:
  //   1. Thread.contextClassLoader — coroutine pool threads (DefaultDispatcher-worker-N) inherit
  //      contextClassLoader from the spawning thread and outlive the action.
  //   2. ThreadLocalMap entries — requires --add-opens=java.base/java.lang=ALL-UNNAMED;
  //      degrades gracefully to a no-op if reflection is denied.
  //   3. Threads whose class was loaded by kspClassLoader — interrupt+join as a safety net.
  private fun cleanupLeaks() {
    val systemCl = ClassLoader.getSystemClassLoader()

    // Production mode: the Application is a shared singleton that persists across all actions in
    // the same worker JVM (lives in sharedCl). Do NOT call disposeApplicationEnvironment() between
    // actions — that would null out ApplicationManager.getApplication(), causing NPEs in the next
    // action's FIR builder. The Application is cleaned up when the JVM exits.
    val appDisposed = false

    // Enumerate all live threads via ThreadGroup traversal (public API, no reflection).
    var root = Thread.currentThread().threadGroup
    while (root.parent != null) root = root.parent!!
    val buf = arrayOfNulls<Thread>(root.activeCount() + 16)
    val n = root.enumerate(buf, true)

    // 1. Reset context classloaders (no reflection, safe to call cross-thread).
    var ctxResets = 0
    for (i in 0 until n) {
      val t = buf[i] ?: continue
      runCatching {
        if (t.contextClassLoader === classLoader) { t.contextClassLoader = systemCl; ctxResets++ }
      }
    }

    // 2. Clear ThreadLocal entries on all threads.
    val tlCleared = clearThreadLocals(classLoader, buf, n)

    // 3. Interrupt any threads whose CLASS was loaded by kspClassLoader that are still alive
    // after Application disposal (safety net).
    val kspThreads =
      (0 until n)
        .mapNotNull { buf[it] }
        .filter { it !== Thread.currentThread() && it.isAlive && it.javaClass.classLoader === classLoader }
    for (t in kspThreads) {
      runCatching { t.interrupt() }
    }
    val deadline = System.currentTimeMillis() + 5_000
    for (t in kspThreads) {
      runCatching {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining > 0) t.join(remaining)
      }
    }
    val kspThreadsDied = kspThreads.count { !it.isAlive }
    val kspThreadNames = kspThreads.map { "${it.name}(${it.javaClass.simpleName})" }

    System.err.println(
      "[KSP2-cleanup] threads=$n app-disposed=$appDisposed ctx-resets=$ctxResets tl-cleared=$tlCleared" +
        " ksp-threads=${kspThreads.size} died=$kspThreadsDied" +
        " killed=$kspThreadNames loader=${classLoader.hashCode()}",
    )
  }

  private fun clearThreadLocals(
    loader: ClassLoader,
    threads: Array<Thread?>,
    count: Int,
  ): Int {
    // Obtain reflective handles once; any failure returns early (degrades gracefully).
    val entryValueField =
      runCatching {
        Class.forName("java.lang.ThreadLocal\$ThreadLocalMap\$Entry")
          .getDeclaredField("value")
          .also { it.isAccessible = true }
      }.getOrNull() ?: return 0

    val mapFields =
      listOfNotNull(
        runCatching {
          Thread::class.java.getDeclaredField("threadLocals").also { it.isAccessible = true }
        }.getOrNull(),
        runCatching {
          Thread::class.java
            .getDeclaredField("inheritableThreadLocals")
            .also { it.isAccessible = true }
        }.getOrNull(),
      )
    if (mapFields.isEmpty()) return 0

    val tableField =
      runCatching {
        Class.forName("java.lang.ThreadLocal\$ThreadLocalMap")
          .getDeclaredField("table")
          .also { it.isAccessible = true }
      }.getOrNull() ?: return 0

    val currentThread = Thread.currentThread()
    var cleared = 0

    for (i in 0 until count) {
      val thread = threads[i] ?: continue
      val isCurrentThread = thread === currentThread
      for (mapField in mapFields) {
        runCatching {
          val map = mapField.get(thread) ?: return@runCatching
          val table = tableField.get(map) as? Array<*> ?: return@runCatching
          for (entry in table) {
            entry ?: continue
            val value = entryValueField.get(entry) ?: continue
            if (value.javaClass.classLoader === loader) {
              if (isCurrentThread) {
                // ThreadLocal.remove() is the correct API for the current thread —
                // it handles map rehashing internally.
                (entry as? java.lang.ref.WeakReference<*>)
                  ?.get()
                  .let { it as? ThreadLocal<*> }
                  ?.remove()
              } else {
                // For other threads we can't call remove() — null the value directly.
                // The entry stays in the map but no longer roots the classloader.
                entryValueField.set(entry, null)
              }
              cleared++
            }
          }
        }
      }
    }

    return cleared
  }

  /**
   * Shut down kotlinx-coroutines dispatchers loaded inside this invoker's classloader.
   *
   * Workaround for KT-84566 / google/ksp#2817. Only runs if kotlinx-coroutines is
   * actually owned by [classLoader]; if it lives in a parent (e.g. on the worker's
   * system classpath), `DefaultScheduler.INSTANCE` is a process-wide singleton shared
   * across all requests and shutting it down would break subsequent invocations with
   * RejectedExecutionException.
   *
   * The IntelliJ kotlinx-coroutines variant bundled with KSP2 does not expose the
   * public `Dispatchers.shutdown()` extension, so we call the internal entry points
   * directly. Resolved reflectively because kotlinx-coroutines-core is not on
   * Ksp2Invoker's compile classpath.
   */
  fun shutdown() {
    try {
      val scheduler = Class.forName("kotlinx.coroutines.scheduling.DefaultScheduler", true, classLoader)
      if (scheduler.classLoader === classLoader) {
        val instance = scheduler.getField("INSTANCE").get(null)
        scheduler.getMethod("shutdown\$kotlinx_coroutines_core").invoke(instance)
      }
    } catch (t: Throwable) {
      System.err.println("KSP2 DefaultScheduler shutdown skipped/failed: $t")
    }
    try {
      val executor = Class.forName("kotlinx.coroutines.DefaultExecutor", true, classLoader)
      if (executor.classLoader === classLoader) {
        val instance = executor.getField("INSTANCE").get(null)
        executor.getMethod("shutdown").invoke(instance)
      }
    } catch (t: Throwable) {
      System.err.println("KSP2 DefaultExecutor shutdown skipped/failed: $t")
    }
  }
}
