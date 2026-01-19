# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Kotlin Spring Boot application that serves as the server component for the lemon-pi racing telemetry system. It provides gRPC-based communication between race cars and pit crews, handling real-time messaging, telemetry data, and race position updates.

## Key Architecture Components

- **Server.kt**: Main gRPC service handling car-to-pit and pit-to-car communication flows
- **AdminService.kt**: Administrative interface for race management and data source control
- **Event System**: Event-driven architecture in `/event/` with handlers for race status, lap completion, and car telemetry
- **Race Data Sources**: Multiple data source handlers in `/racedata/` (DS1, DS2) for different racing timing systems
- **Security**: gRPC interceptors for authentication and context management
- **External Integrations**: Slack notifications, Firebase/Firestore for data persistence

## Development Commands

### Local Development
```bash
# Start Firestore emulator (required for local development)
gcloud emulators firestore start --host-port=0.0.0.0:8080

# Run application locally with dev profile
./gradlew bootRun --args='--spring.profiles.active=dev'
```

### Build and Test
```bash
# Build the project
./gradlew build

# Run tests
./gradlew test

# Run specific test
./gradlew test --tests "TestClassName"
```

## Configuration

- **application.properties**: Main configuration with gRPC port (9090), admin credentials, and logging levels
- **application-dev.properties**: Development-specific overrides
- Environment variables: `PORT`, `ADMIN_USERNAME`, `ADMIN_PASSWORD`, `COOLANT_ALERT_LEVEL`
- GitHub Packages dependency: `lemon-pi-protos` library for protocol buffer definitions

## Key Dependencies

- Spring Boot 2.5.7 with gRPC server support
- Kotlin coroutines for async message handling
- Google Cloud Firestore for data persistence
- Slack API client for notifications
- Microsoft Playwright for web scraping race data
- Protocol Buffers for message serialization

## Message Flow Architecture

The system maintains bidirectional communication channels:
- `toPitIndex`: Track/car mapping for messages from cars to pits
- `toCarIndex`: Track/car mapping for messages from pits to cars
- Channels use `MutableSharedFlow` with buffer overflow handling
- Authentication via radio keys to prevent channel hijacking