// Replace org.eclipse.jgit.lib.InflaterCache in the JGit jar (D-033, LESSONS L-036).
//
// JGit 5.13 pools Inflaters; Android's InflaterInputStream.close() ends them, so streaming any object
// larger than streamFileThreshold crashes ("Inflater has been closed"). gitcore ships a non-pooling
// InflaterCache with the same API (gitcore/src/main/java/org/eclipse/jgit/lib/InflaterCache.java);
// this artifact transform removes the original class from the org.eclipse.jgit jar so only ours is
// on the runtime classpath. Signatures are removed from every org.eclipse.jgit* jar: the JVM rejects
// classes of one package coming from differently signed jars (org.eclipse.jgit.transport is split
// between the core and the ssh.jsch jar). Android does not check jar signatures; APK packaging drops them.
//
// Applied by :gitcore and :app with `apply(from = ...)`; affects runtime classpaths only.

import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

val jgitPatched: Attribute<Boolean> = Attribute.of("vaultsync.jgitPatched", Boolean::class.javaObjectType)

abstract class StripJGitInflaterCache : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        if (!input.name.startsWith("org.eclipse.jgit")) {
            outputs.file(input)
            return
        }
        // The class is removed only from the JGit core jar (org.eclipse.jgit-<version>.jar)
        val core = CORE_JAR.matches(input.name)
        val output = outputs.file(input.name.removeSuffix(".jar") + "-vaultsync.jar")
        var removed = false
        ZipInputStream(input.inputStream().buffered()).use { zin ->
            ZipOutputStream(output.outputStream().buffered()).use { zout ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val name = e.name
                    val isTarget = core && name == TARGET
                    if (isTarget) removed = true
                    if (isTarget || SIGNATURE.matches(name)) continue
                    zout.putNextEntry(ZipEntry(name).apply { time = e.time })
                    zin.copyTo(zout)
                    zout.closeEntry()
                }
            }
        }
        check(removed || !core) { "$TARGET not found in ${input.name}; check the JGit version (D-033)" }
    }

    companion object {
        const val TARGET = "org/eclipse/jgit/lib/InflaterCache.class"
        val CORE_JAR = Regex("org\\.eclipse\\.jgit-\\d[^/]*\\.jar")
        val SIGNATURE = Regex("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")
    }
}

dependencies {
    attributesSchema { attribute(jgitPatched) }
    artifactTypes.maybeCreate("jar").attributes.attribute(jgitPatched, false)
    registerTransform(StripJGitInflaterCache::class) {
        from.attribute(jgitPatched, false).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
        to.attribute(jgitPatched, true).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
    }
}

configurations.configureEach {
    // Runtime classpaths only (compile classpaths keep the original jar; the API is identical)
    if (isCanBeResolved && name.endsWith("RuntimeClasspath", ignoreCase = true)) {
        attributes.attribute(jgitPatched, true)
    }
    // Project outputs (gitcore's jar / classes / resources variants) need no transform; saying so keeps
    // the consumer's variant selection unambiguous
    if (isCanBeConsumed && name.endsWith("Elements")) {
        attributes.attribute(jgitPatched, true)
        outgoing.variants.configureEach { attributes.attribute(jgitPatched, true) }
    }
}
