# ntfy Android

A simplified Android app for [ntfy](https://ntfy.sh) - Send push notifications to your phone or desktop via HTTP PUT/POST.

## Features

- **Message Reception**: HTTP Long Polling connection to ntfy servers
- **Subscription Management**: Add/remove topic subscriptions
- **Local Storage**: Uses DataStore to save subscriptions
- **Notification Display**: Shows notifications when messages arrive
- **Foreground Service**: Keeps running in background to receive messages

## Tech Stack

- Regular Android App (Not Wear OS)
- minSdk: 21
- targetSdk: 34
- Package: com.ntfy.wear

## Building

```bash
./gradlew assembleDebug
```

## Usage

1. Add a subscription by entering a topic name
2. The app will connect via long polling and receive notifications
3. Enable "instant" delivery for real-time notifications

## License

MIT
