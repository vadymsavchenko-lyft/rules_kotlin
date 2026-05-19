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

    // Set context classloader to the shared KSP2 classloader before invoking.
    // IntelliJ's PluginXmlPathResolver resolves plugin XML resources via
    // Thread.currentThread().contextClassLoader — without this, the second+ action
    // in a persistent worker gets "Stream closed" when the previous action's
    // (now-closed) classloader is still set as the context classloader.
    val previousContextCl = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = classLoader
    return try {
      KotlinSymbolProcessing(kspConfig, processors, KspGradleLogger(logLevel)).execute().code
    } finally {
      Thread.currentThread().contextClassLoader = previousContextCl
    }
  }
}
