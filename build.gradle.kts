plugins {
    kotlin("jvm") version "2.3.10"     // any recent Kotlin
    application
}
repositories {
    // PR #1814 branch build, published locally from ~/projects/clinical_quality_language
    // (./gradlew publishToMavenLocal on branch fhir-json-to-cql-value-parser).
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") } // v5 SNAPSHOTs (verified live)
}
// All org.cqframework modules must share one version; mixing 5.0.0 with the branch build
// would put two copies of the engine classes on the classpath.
val cqlVersion = "5.1.0-kmp-fhir-providers-84476e31-SNAPSHOT" // PR #1815 (includes #1814)
dependencies {
    implementation("org.cqframework:engine:$cqlVersion")
    implementation("org.cqframework:cql-to-elm:$cqlVersion")
    implementation("org.cqframework:engine-fhir:$cqlVersion")   // ← PR #1814: FHIR JSON -> CQL Value parser
    implementation("dev.ohs.fhir:fhir-model-r4:1.0.0-beta05")   // ← NEW: kotlin-fhir R4 model (KMP)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
    implementation(kotlin("reflect"))
}

application { mainClass.set("MainKt") }
