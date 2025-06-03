# Gobble-O-Clock v2

A Wear OS application that monitors your heart rate and provides intelligent meal timing notifications based on biometric data.

## Overview

Gobble-O-Clock v2 is a smart health monitoring app designed for Wear OS devices that tracks your heart rate continuously and determines optimal meal timing based on your physiological state. The app transitions to "GOBBLE_TIME" when conditions indicate it's an appropriate time to eat, providing timely notifications to help maintain healthy eating schedules.

## Features

- **Continuous Heart Rate Monitoring**: Uses Wear OS Health Services for real-time heart rate tracking
- **Smart Meal Timing**: Analyzes biometric data to determine optimal eating times
- **Background Service**: Runs continuously in the background to monitor health metrics
- **Push Notifications**: Alerts users when transitioning to "GOBBLE_TIME" state
- **Exact Alarm Integration**: Precise timing for meal reminders
- **Wear OS Optimized**: Built specifically for smartwatch interfaces using Jetpack Compose

## Technical Stack

- **Platform**: Wear OS (Android)
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose for Wear OS
- **Architecture**: MVVM with clean architecture (data/logic/presentation/service layers)
- **Health Integration**: Google Health Services Client
- **Minimum SDK**: Android 11 (API 30)
- **Target SDK**: Android 14 (API 35)

## Permissions

The app requires the following permissions:
- `BODY_SENSORS` - Access to heart rate sensor
- `FOREGROUND_SERVICE` - Background heart rate monitoring
- `FOREGROUND_SERVICE_HEALTH` - Health-specific foreground service
- `POST_NOTIFICATIONS` - Meal timing notifications
- `SCHEDULE_EXACT_ALARM` - Precise alarm scheduling
- `WAKE_LOCK` - Keep device awake for monitoring

## Project Structure

```
app/src/main/java/com/example/gobble_o_clockv2/
├── data/           # Data layer and repositories
├── logic/          # Business logic and algorithms
├── presentation/   # UI components and ViewModels
└── service/        # Background services
```

## Development Status

This is version 2 of the Gobble-O-Clock application. Current development priorities include:

- [ ] Implement alert mechanism for GOBBLE_TIME state transitions
- [ ] Integrate POST_NOTIFICATIONS permission handling for Android 13+
- [ ] Add notification display with vibration and sound
- [ ] Enhance UI permission checking logic

## Building and Running

### Prerequisites
- Android Studio Arctic Fox or later
- Wear OS emulator or physical Wear OS device
- Android SDK with API 35

### Setup
1. Clone the repository:
   ```bash
   git clone https://github.com/rickoneeleven/gobble-o-clockv2.git
   ```

2. Open the project in Android Studio

3. Sync Gradle dependencies

4. Connect a Wear OS device or start a Wear OS emulator

5. Run the application

### Build Commands
```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

## Architecture

The app follows clean architecture principles with distinct layers:

- **Presentation Layer**: Jetpack Compose UI components and ViewModels
- **Logic Layer**: Business logic for health data analysis and state management
- **Data Layer**: Health Services integration and data persistence
- **Service Layer**: Background heart rate monitoring service

## Health Services Integration

The app integrates with Wear OS Health Services to:
- Access real-time heart rate data
- Monitor sensor availability
- Handle permission requirements
- Manage battery optimization

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Acknowledgments

- Google Health Services team for Wear OS health integration APIs
- Jetpack Compose team for modern UI framework
- Wear OS development community

## Support

For questions or issues, please open an issue on GitHub or contact the development team.

---

*Gobble-O-Clock v2 - Smart meal timing through biometric monitoring*