## Advanced autofish
- Module equipped to play a server-side fishing minigame automatically.
- Minecraft meteor client 1.21.4 addon.
- Multiple settings to adjust to fit different needs.
- Works by casting the rod, waiting for it to bob, detecting a bite, reeling in and starting the minigame:
- Spots the two minigame pieces (the fish and the box) by watching nearby ItemDisplay entities
- Reads each piece’s Y position (prefers local transform Y, falls back to world Y) and decides which one moves (fish) vs stays (box).
- Computes the difference between fish and box and taps sneak to nudge the bar toward center (simple PD-style logic with tiny jitter).
- Uses short history to estimate velocity, so it doesn’t overcorrect when the fish pops up/down.
- Ends the cycle when the overlay says caught/failed, then cools down and re-casts.
- Light humanized delays to feel natural
- AntiAFK and AdvancedReconnect modules to keep the bot fishing for long periods of time and reconnect on server restarts automatically.

https://github.com/user-attachments/assets/e7e9a664-73be-480d-a2c5-32452f5917b6
