plugins {
    id("io.quarkus")
}

val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:$quarkusPlatformVersion"))
    implementation("io.quarkus:quarkus-agroal")
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
}
