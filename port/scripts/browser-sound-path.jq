def browser_sound_path:
  startswith("minecraft/sounds/")
  and endswith(".ogg")
  and (startswith("minecraft/sounds/music/") | not)
  and (startswith("minecraft/sounds/records/") | not);
