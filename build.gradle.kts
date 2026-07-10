plugins {
    kotlin("jvm") version "2.3.10"     // any recent Kotlin
    application
}
repositories {
    mavenCentral()
    maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") } // v5 SNAPSHOTs (verified live)
}
dependencies {
    implementation("org.cqframework:engine:5.0.0")
    implementation("org.cqframework:cql-to-elm:5.0.0")
    implementation("dev.ohs.fhir:fhir-model-r4:1.0.0-beta05")   // ← NEW: kotlin-fhir R4 model (KMP)
    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
		implementation(kotlin("reflect"))
}

application { mainClass.set("MainKt") }
