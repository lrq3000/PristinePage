import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.security.MessageDigest

// Acquisition only: no compilation/test task dependencies, classloading or verification writer. Configuration executes the already approved build plugins, not EJML. Inspect the graph before requesting its JAR files.
gradle.projectsEvaluated {
    val core = rootProject.project(":core")
    val oldCoordinates = core.file("gradle.lockfile").readLines().filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("empty=") }.map { it.substringBefore("=") }.toSet()
    val expected = mapOf(
        "ejml-ddense" to "5e056bb27c1f39e612e8d05b3467bf1f05d0dc6c73f6b73a85fddf73127f901f",
        "ejml-core" to "60e239ad8ad7b255542cab56986edec88f14feecdf86468b1ad37a7a7afe52e2"
    )
    val allowed = oldCoordinates + expected.keys.map { "org.ejml:$it:0.46.1" }
    core.tasks.register("resolveApprovedSvd") {
        doLast {
            for (name in listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath")) {
                val configuration = core.configurations.getByName(name)
                val coordinates = configuration.incoming.resolutionResult.allComponents.mapNotNull { component ->
                    (component.id as? ModuleComponentIdentifier)?.let { "${it.group}:${it.module}:${it.version}" }
                }.toSet()
                check((coordinates - allowed).isEmpty()) { "Unapproved graph: ${coordinates - allowed}" }
                check(expected.keys.all { "org.ejml:$it:0.46.1" in coordinates }) { "Missing exact approved SVD components" }
                val artifacts = configuration.resolvedConfiguration.resolvedArtifacts.filter { it.moduleVersion.id.group == "org.ejml" }
                check(artifacts.size == 2) { "Unexpected EJML artifacts" }
                for (artifact in artifacts) {
                    val id = artifact.moduleVersion.id
                    check(id.version == "0.46.1" && artifact.type == "jar" && artifact.classifier == null)
                    val file = artifact.file
                    check(file.length() <= 1024 * 1024) { "Artifact exceeded its bounded size" }
                    val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                    check(digest == expected[id.name]) { "Unexpected artifact hash: $id" }
                    println("SVD_VERIFIED $name $id ${file.length()} $digest ${file.absolutePath}")
                }
            }
        }
    }
}
