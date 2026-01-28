# AirSeriesTester

A minimal Android app that tries to read RayNeo Air-series sensor data over USB and uses it to
rotate an on-screen image (3DoF yaw/pitch/roll). The current parser contains placeholder offsets
and scaling; replace them with the values from `driver_rayneo.cpp` in the RayNeo-Air-3S-Pro-OpenVR
project when you have access to that file.

## What it does
- Scans connected USB devices for a bulk IN endpoint.
- Requests permission and starts reading packets.
- Parses yaw/pitch/roll from each packet and applies rotations to the image.

## Next steps
- Update `RayNeoPacketParser` offsets and scaling constants.
- Add device-specific filtering (VID/PID) once confirmed.
- Consider adding a foreground service to keep the USB connection alive.
