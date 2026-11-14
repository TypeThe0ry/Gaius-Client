#!/usr/bin/env python3
"""Regression guard for the browser Create World command default patch."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATCHER = ROOT / "port/tools/src/main/java/dev/gaius/tools/patches/ui/UiPatches.java"
QUICK_CHECK = Path(__file__).resolve().parent / "legacy/quick-check.py"

source = PATCHER.read_text(encoding="utf-8")
checks = QUICK_CHECK.read_text(encoding="utf-8")
section = source.split("patchCreateWorldScreenBrowserDefaults", 1)[1].split("public static void", 1)[0]
assert "foundAllowCommands" in section
assert "WorldCreationUiState" in section and "isAllowCommands" in section
assert "setAllowCommands" not in section
assert "ICONST_1" not in section
assert "WorldCreationUiState.isAllowCommands" in checks
assert "and \"WorldCreationUiState.setAllowCommands:(Z)V\" not in create_world_constructor" in checks
server = (ROOT / "port/src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java").read_text()
configure = server.split("public static void configurePlayerList(", 1)[1].split("@JSExport", 1)[0]
assert "playerList.getServer().getWorldData().isAllowCommands()" in configure
assert "setAllowCommandsForAllPlayers(playerList, allowCommands)" in configure
assert '"commands=" + allowCommands' in configure
assert "allowCommandsForAllPlayers(playerList)" not in configure
assert "BrowserPlayerListCompat.allowCommandsForAllPlayers(playerList)" not in checks
assert "setAllowCommandsForAllPlayers(true)" not in checks
assert checks.count('"BrowserPlayerListCompat.setAllowCommandsForAllPlayers(playerList, allowCommands)"') == 2
assert checks.count('"getWorldData().isAllowCommands()"') == 2
print("vanilla world command default patch smoke passed")
