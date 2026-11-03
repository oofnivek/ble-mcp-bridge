# BLE MCP Bridge

An Android app that lets Claude Code use the phone's Bluetooth Low Energy radio through an MCP server.

```
Claude Code on Mac -> phone hotspot -> MCP server in the app -> BLE -> device
```

## Build and install

Connect the phone with USB debugging on, then run:

```sh
./gradlew installDebug
```

## Connect Claude Code

1. Turn on the phone's hotspot and join it from the Mac.
2. Open **BLE MCP Bridge** and tap **Start bridge**.
3. Tap **Disable battery optimization**, and turn off hotspot auto-off if your phone has that setting.
4. Run the `claude mcp add` command shown in the app for the hotspot interface. The name varies by phone, such as `ap0`, `swlan0`, or `wlan1`.
5. In Claude Code, run `/mcp` to check the connection.

The address may change when the hotspot restarts. If it does, tap **Refresh** and run `claude mcp remove ble-bridge` before adding it again.

## Tools

| Tool | Purpose |
|---|---|
| `ble_status` | Show Bluetooth state, connected devices, the write setting, and recent disconnect reasons |
| `ble_scan` | Find nearby devices and their broadcast data. With the screen off, it needs an `address` or `service_uuid` filter |
| `ble_connect` | Connect and list services |
| `ble_read_all` | Connect and read every readable value in one call |
| `ble_list_services` | List services and characteristics |
| `ble_read` | Read a characteristic |
| `ble_write` | Write to a characteristic, only when allowed in the app |
| `ble_subscribe` | Turn notifications on or off |
| `ble_read_notifications` | Collect buffered notifications |
| `ble_disconnect` | Disconnect from a device |

## Security

- Every MCP request needs the bearer token shown in the app.
- `ble_write` is blocked until **Allow writes** is on, and that setting resets when the bridge restarts.
- Traffic is plain HTTP, so keep the hotspot password-protected and only let devices you trust join it.
- Only connect to devices you own or have permission to control.
