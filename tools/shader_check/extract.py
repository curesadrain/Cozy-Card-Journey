#!/usr/bin/env python3
"""Copies shader sources and card texture layers into this folder for render.js."""
import json, os, re, shutil
here = os.path.dirname(os.path.abspath(__file__))
root = os.path.join(here, "..", "..")
src = open(os.path.join(root, "app/src/main/java/com/cozycards/app/renderer/CardShaders.kt")).read()
shaders = {n: re.search(n + r' = """(.*?)"""', src, re.S).group(1) for n in ["CARD_VERTEX", "CARD_FRAGMENT", "BG_VERTEX", "BG_FRAGMENT"]}
json.dump(shaders, open(os.path.join(here, "shaders.json"), "w"))
shots = os.path.join(root, "app/build/screenshots")
for tag, cid in [("L", "spiderman_028"), ("E", "bts_027"), ("R", "tmnt_004"), ("C", "bts_001")]:
    shutil.copy(os.path.join(shots, f"layer_frame_{cid}.png"), os.path.join(here, f"frame{tag}.png"))
    shutil.copy(os.path.join(shots, f"layer_art_{cid}.png"), os.path.join(here, f"art{tag}.png"))
shutil.copy(os.path.join(shots, "back_BACK_CLASSIC.png"), os.path.join(here, "back.png"))
print("ok")
