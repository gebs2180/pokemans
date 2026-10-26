plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.springframework.modulith:spring-modulith-bom:2.1.1"))
    implementation(platform("software.amazon.awssdk:bom:2.56.0"))

    implementation(project(":valuation-core"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("software.amazon.awssdk:s3")
    implementation("com.drewnoakes:metadata-extractor:2.21.0")
    implementation("org.openpnp:opencv:4.9.0-0")
    // Report PDFs, rendered in the JVM: Thymeleaf (Apache-2.0) fills XHTML templates and OpenHTMLtoPDF
    // (LGPL-2.1, used unmodified as a library; PDFBox underneath is Apache-2.0) lays them out.
    implementation("org.thymeleaf:thymeleaf")
    implementation("io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.85")
    // Error reports to Sentry (docs/hosting.md). Does nothing unless SENTRY_DSN is set.
    implementation("io.sentry:sentry-spring-boot-4-starter:8.61.0")
    implementation("io.sentry:sentry-logback:8.61.0")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-minio")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// OpenCV loads its bundled native library through JNI, which Java 24+ warns about unless allowed.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // ./gradlew test -Dgolden.update=true rewrites the golden report files (GoldenReportTest).
    systemProperty("golden.update", System.getProperty("golden.update") ?: "false")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// End-to-end runs (e2e/): the app plus the tests' fake shipping provider, so a taken card gets a label without
// EasyPost. Only the e2e suite uses this source set; the boot jar and the production image never contain it.
val e2e by sourceSets.creating {
    java {
        srcDir("src/test/java")
        include("com/pokemans/app/offers/FakeShippingProvider.java", "com/pokemans/app/offers/E2eApplication.java")
    }
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[e2e.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[e2e.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
dependencies {
    // FakeShippingProvider is a @TestConfiguration; E2eApplication adds it to the app.
    "e2eImplementation"("org.springframework.boot:spring-boot-test")
}

// Compiles the e2e classpath and writes a java argfile for it, so e2e/ starts the app as a plain java process it can
// stop (a bootRun would live in the Gradle daemon). Run it with: java @app/build/e2e/java.args
tasks.register("e2eArgs") {
    description = "Writes build/e2e/java.args: the app with the fake shipping provider, for the browser tests in e2e/."
    val classpath = e2e.runtimeClasspath
    dependsOn(classpath)
    val out = layout.buildDirectory.file("e2e/java.args")
    outputs.file(out)
    doLast {
        val path = classpath.files.joinToString(File.pathSeparator) { it.absolutePath }
        val lines = listOf("--enable-native-access=ALL-UNNAMED", "-cp", "\"" + path.replace('\\', '/') + "\"",
            "com.pokemans.app.offers.E2eApplication")
        out.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
