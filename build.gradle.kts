import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val protobufVersion: String by project
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
java.sourceCompatibility = JavaVersion.VERSION_17

repositories {
	mavenCentral()
	maven {
		name = "GitHubPackages"
		url = uri("https://maven.pkg.github.com/sprintf/lemon-pi-protos")
		credentials {
			username = System.getenv("GITHUB_ACTOR")
			password = System.getenv("GITHUB_TOKEN")
		}
	}
}

extra["springCloudGcpVersion"] = "2.0.6"
extra["springCloudVersion"] = "2020.0.4"
extra["gcpLibrariesVersion"] = "26.17.0"

dependencies {
	implementation("com.normtronix:lemon-pi-protos:1.2")
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

	implementation("com.google.protobuf:protobuf-java:${protobufVersion}")
	implementation("io.grpc:grpc-protobuf:${grpcVersion}")
	implementation("io.grpc:grpc-stub:${grpcVersion}")
	implementation("io.grpc:grpc-kotlin-stub:0.1.5")
	implementation("com.google.cloud:google-cloud-firestore")
	implementation("com.slack.api:slack-api-client:1.27.1")
	implementation("com.auth0:java-jwt:4.4.0")
	implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

	implementation("com.pusher:pusher-java-client:2.4.2")

	compileOnly("jakarta.annotation:jakarta.annotation-api:1.3.5") // Java 9+ compatibility - Do NOT update to 2.0.0

	implementation("org.yaml:snakeyaml:1.29")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("io.grpc:grpc-testing:${grpcVersion}")
	testImplementation("net.devh:grpc-client-spring-boot-starter:2.13.0.RELEASE")
	testImplementation("io.mockk:mockk:1.12.2")
	testImplementation("io.ktor:ktor-client-mock:$ktorVersion")

	implementation("com.microsoft.playwright:playwright:1.47.0")

}

dependencyManagement {
	imports {
		mavenBom("com.google.cloud:spring-cloud-gcp-dependencies:${property("springCloudGcpVersion")}")
		mavenBom("com.google.cloud:libraries-bom:${property("gcpLibrariesVersion")}")
		mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
	}
}

tasks.withType<KotlinCompile> {
	kotlinOptions {
		freeCompilerArgs = listOf("-Xjsr305=strict")
		jvmTarget = "17"
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}