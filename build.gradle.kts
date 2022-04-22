import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.springframework.boot.gradle.tasks.bundling.BootJar

val protobufVersion: String by project
val protobufPluginVersion: String by project
val grpcVersion: String by project
val ktorVersion: String by project


plugins {
	idea
	application
	id("org.springframework.boot") version "2.5.7"
	id("io.spring.dependency-management") version "1.0.11.RELEASE"
	kotlin("jvm")
	kotlin("plugin.spring")
}

group = "com.normtronix"
version = "0.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_11

repositories {
	mavenCentral()
}

extra["springCloudGcpVersion"] = "2.0.6"
extra["springCloudVersion"] = "2020.0.4"

dependencies {

	implementation(project(":lemon-pi-protos"))
	implementation("org.springframework.boot:spring-boot-starter")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("com.google.code.gson:gson:2.8.9")
	implementation("io.ktor:ktor-client-websockets:$ktorVersion")
	implementation("io.ktor:ktor-client-cio:$ktorVersion")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

	implementation("net.devh:grpc-server-spring-boot-starter:2.13.0.RELEASE")

	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2")

	implementation("io.grpc:grpc-protobuf:${grpcVersion}")
	implementation("io.grpc:grpc-stub:${grpcVersion}")
	implementation("io.grpc:grpc-kotlin-stub:0.1.5")
	compileOnly("jakarta.annotation:jakarta.annotation-api:1.3.5") // Java 9+ compatibility - Do NOT update to 2.0.0

	implementation("org.yaml:snakeyaml:1.29")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("io.grpc:grpc-testing:${grpcVersion}")
	testImplementation("net.devh:grpc-client-spring-boot-starter:2.13.0.RELEASE")
	testImplementation("io.mockk:mockk:1.12.2")
}

dependencyManagement {
	imports {
		mavenBom("com.google.cloud:spring-cloud-gcp-dependencies:${property("springCloudGcpVersion")}")
		mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
	}
}

tasks.withType<KotlinCompile> {
	kotlinOptions {
		freeCompilerArgs = listOf("-Xjsr305=strict")
		jvmTarget = "11"
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}