# Fartiqs Motion Blur (Fabric, Minecraft 1.21.11)

Press **.** (period) in game to open the menu.

## Build with GitHub
1. Create a repo, upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab, wait for the `build` run, download the `fartiqs-motionblur` artifact.
3. Put the jar in `.minecraft/mods` with Fabric API.

If the build fails, open the failed run and copy the red error lines.

## If something still doesn't work
Open the menu (J). The bottom of the panel shows live status:
- `Blur: ACTIVE` means the effect is really being drawn. `Blur: ERROR ...` shows why it isn't.
- `Aspect hook: NOT CALLED YET` means the projection method name has changed.
Also check `.minecraft/logs/latest.log` for `[Fartiqs Motion Blur]`.
