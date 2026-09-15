# 06-pre-gen-buffer

## Objective

For the /uhc preset, there are another thing I want to do: since the world generation takes a really long time, for each saved preset in the preset list, add a pre-generate slots, three for each preset, that the host pc will set a time interval that checks the all those slots and if any slots if available. If there are slots availble, the server will generate based on the preset config and saved this preset to the slot. The player in the game can use the pre-generate world to save time. This pre-gen works as a buffer, where it takes idling resources to generate world before hand.

## Goal

1. For each preset config, there should be three slots that the server can pre-generate the world.
2. The user can list, load, save, and name the saved slot. Similar to the MCDR plugin prime backup:
   1. https://tisunion.github.io/PrimeBackup/
   2. https://github.com/TISUnion/PrimeBackup
3. Need to figure out a way to use the hardware resource on the server PC that to pre-generate worlds based on config to the corresponding slots whenever there are enough resource or the terminal is idling.
4. This feature by default is closed. Player need to turn on maunally in the game or terminal by `/uhc <command>`.

## No Goal

1. We will not use MCDR and prime backup
2. We don't want to make additional software or running session on the server back end to manually run to pre-gen world.
