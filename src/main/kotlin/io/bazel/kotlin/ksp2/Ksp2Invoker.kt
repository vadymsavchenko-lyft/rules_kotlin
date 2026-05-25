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
  ): Int {
    // Load processors via ServiceLoader from the provided classloader
    val processors =
      ServiceLoader.load(SymbolProcessorProvider::class.java, classLoader).toList()

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
          this.experimentalPsiResolution = true
        }.build()

    // Create logger and execute
    val logger = KspGradleLogger(logLevel)
    val ksp = KotlinSymbolProcessing(kspConfig, processors, logger)

    return ksp.execute().code
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
