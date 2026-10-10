#!/usr/bin/env python3
"""Planta d'un pis (tall horitzontal) d'una zona del món generat, amb llegenda de lletres.
Ús: floor_plan.py <region> <x0> <z0> <x1> <z1> <y>"""
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from iso_render import blocks

reg = sys.argv[1]
x0, z0, x1, z1, y = map(int, sys.argv[2:7])
B = blocks(reg, x0, z0, x1, z1, y, y)
SYM = {"spruce_stairs": "s", "oak_stairs": "c", "dark_oak_stairs": "S", "barrel": "b", "water_cauldron": "~", "smoker": "o",
       "furnace": "o", "iron_block": "F", "white_bed": "B", "lime_bed": "B", "light_blue_bed": "B", "chest": "C",
       "bookshelf": "L", "oak_fence": "t", "spruce_fence": "t", "white_concrete": "#", "oak_door": "d", "birch_door": "d",
       "spruce_door": "D", "glass_pane": "w", "decorated_pot": "P", "polished_andesite": "A", "lectern": "l",
       "jukebox": "J", "stonecutter": "T", "blast_furnace": "K", "clay": "c", "hopper": "h", "loom": "M",
       "smooth_stone": "_", "dark_oak_planks": "W", "crafting_table": "+", "spruce_planks": "p", "lantern": "*"}
for z in range(z0, z1):
    row = ""
    for x in range(x0, x1):
        n = B.get((x, y, z))
        if n is None:
            row += "."
        elif n in SYM:
            row += SYM[n]
        elif "carpet" in n:
            row += ","
        elif "potted" in n or n == "flower_pot":
            row += "f"
        elif "wool" in n:
            row += "m"
        else:
            row += "?"
    print(row)
