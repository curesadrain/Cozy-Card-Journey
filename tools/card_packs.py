#!/usr/bin/env python3
"""Authoring script for the bundled starter card packs.

All starter cards are ORIGINAL_ART: procedural illustrations made for this game, inspired by
the themes. No physical-card data (manufacturer, year, number, original rarity) is claimed,
because none has been verified. Real cards can be added later with VERIFIED/INCOMPLETE status
and a cited source; the app's CardPackValidator enforces these rules.

Run: python3 tools/card_packs.py  (writes app/src/main/assets/cards/*.json)
"""
import json, os

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "cards")

def card(cid, coll, name, series, rarity, desc, palette, motif, glyph, seed):
    return {
        "id": cid, "name": name, "collectionId": coll, "series": series,
        "manufacturer": None, "year": None, "cardNumber": None, "originalRarity": None,
        "gameRarity": rarity, "description": desc,
        "frontImage": None, "backImage": None,
        "art": {"palette": palette, "motif": motif, "seed": seed, "glyph": glyph},
        "source": None, "verification": "ORIGINAL_ART", "imageRights": "ORIGINAL_ARTWORK",
    }

def pack(coll_id, name, desc, disclaimer, accent, albums):
    cards, album_defs, n = [], [], 0
    for (aid, aname, adesc, entries) in albums:
        ids = []
        for (cname, rarity, motif, glyph, palette, cdesc) in entries:
            n += 1
            cid = f"{coll_id}_{n:03d}"
            ids.append(cid)
            cards.append(card(cid, coll_id, cname, aname, rarity, cdesc, palette, motif, glyph, n * 37))
        album_defs.append({"id": f"{coll_id}_{aid}", "name": aname, "description": adesc, "cardIds": ids})
    return {"schemaVersion": 1, "collection": {"id": coll_id, "name": name, "description": desc,
            "disclaimer": disclaimer, "accentColor": accent, "albums": album_defs}, "cards": cards}

PURPLE = ["#2B1B4A", "#6C4AB6", "#B9A2F0", "#FFE3F1"]
LILAC = ["#3A2459", "#8E6CCB", "#D7C6FF", "#FFFFFF"]
STAGE = ["#120E2A", "#3B2F7A", "#F06EAA", "#FFE08A"]
SUNSET = ["#3D1D3A", "#C8507A", "#F7A072", "#FFF1C9"]

members = [("RM", "RM"), ("Jin", "JN"), ("SUGA", "SG"), ("j-hope", "JH"), ("Jimin", "JM"), ("V", "V"), ("Jung Kook", "JK")]

def bts_album_portraits():
    return [(f"{m} — Lavender Portrait", "COMMON", "MICROPHONE", g, PURPLE,
             f"Original alternate illustration inspired by BTS member {m}: a soft lavender portrait frame. "
             f"Not an official photocard.") for m, g in members]

def bts_album_stage():
    out = []
    for i, (m, g) in enumerate(members):
        r = "RARE" if i % 2 == 0 else "COMMON"
        out.append((f"{m} — Stage Lights", r, "STAGE", g, STAGE,
                    f"Original stage-light illustration inspired by {m}'s performances. Abstract art, no photo likeness."))
    return out

def bts_album_moments():
    names = [("Purple Ribbon", "RARE", "HEART"), ("Seven Stars", "EPIC", "STARS"), ("Encore Night", "RARE", "NOTE"),
             ("Fan Chant Wave", "COMMON", "WAVE"), ("Lilac Skyline", "COMMON", "CITY"), ("Moonlit Dorm", "RARE", "MOON"),
             ("Cherry Concert", "EPIC", "SAKURA"), ("Golden Hour", "COMMON", "LANTERN")]
    return [(f"{n}", r, mo, "BTS", SUNSET if i % 2 else LILAC,
             f"Original group-themed illustration '{n}'. Celebrates the fandom mood; not an official card.")
            for i, (n, r, mo) in enumerate(names)]

def bts_album_specials():
    return [(f"{m} — Holo Signature", "EPIC" if i < 5 else "LEGENDARY", "STARS", g, ["#1A1033", "#7F5AF0", "#2CB1BC", "#FDE68A"],
             f"Original holographic illustration inspired by {m}. Game-exclusive design, not a reproduction of a real autograph card.")
            for i, (m, g) in enumerate(members)]

bts = pack("bts", "BTS", "A fan tribute collection of original illustrations inspired by the seven members of BTS.",
           "Fan tribute with original artwork. Not affiliated with or endorsed by BTS, BIGHIT MUSIC or HYBE. Not real photocards.",
           "#8E6CCB",
           [("portraits", "Lavender Portraits", "Soft portrait frames, one per member.", bts_album_portraits()),
            ("stage", "Stage Lights", "Spotlight-themed illustrations.", bts_album_stage()),
            ("moments", "Purple Moments", "Group mood pieces.", bts_album_moments()),
            ("holo", "Holo Signatures", "Rare holographic designs.", bts_album_specials())])

RED = ["#1B0F14", "#B3122E", "#E94560", "#F5F5F5"]
BLUE = ["#0B1630", "#1F4E99", "#E63946", "#F1FAEE"]
NIGHT = ["#0A0F1F", "#22305C", "#4CC9F0", "#F72585"]
GREEN = ["#0F1F14", "#2D6A4F", "#95D5B2", "#F1FAEE"]
VENOM = ["#050505", "#1E1E2E", "#E0E0E0", "#8D99AE"]

heroes = [("Spider-Man (Peter Parker)", "COMMON", "SPIDER", "PP", RED), ("Miles Morales", "RARE", "LIGHTNING", "MM", ["#0D0D0D", "#B5179E", "#F72585", "#FFD60A"]),
          ("Ghost-Spider (Gwen Stacy)", "RARE", "SPIDER", "GS", ["#101820", "#F4F4F4", "#F15BB5", "#00BBF9"]),
          ("Mary Jane Watson", "COMMON", "HEART", "MJ", SUNSET), ("Aunt May", "COMMON", "LANTERN", "AM", ["#2F1B12", "#A0522D", "#F4A261", "#FFF3E0"]),
          ("J. Jonah Jameson", "COMMON", "CITY", "JJ", ["#1C1C1C", "#5C5C5C", "#E9C46A", "#FFFFFF"]),
          ("Silk", "RARE", "WEB", "SK", ["#1A1A2E", "#E43F5A", "#F6F5F5", "#162447"]),
          ("Spider-Woman", "RARE", "SPIDER", "SW", ["#1B0B0B", "#C1121F", "#FDF0D5", "#FFD60A"]),
          ("Spider-Man 2099", "EPIC", "LIGHTNING", "99", ["#050A30", "#000C66", "#E63946", "#7EC8E3"])]
villains = [("Green Goblin", "RARE", "MASK", "GG", GREEN), ("Doctor Octopus", "RARE", "MASK", "DO", ["#13221B", "#4F772D", "#ECF39E", "#FFFFFF"]),
            ("Venom", "EPIC", "SPIDER", "VN", VENOM), ("Carnage", "EPIC", "SPIDER", "CG", ["#1A0000", "#9B0000", "#FF2E2E", "#FFD6D6"]),
            ("Sandman", "COMMON", "WAVE", "SM", ["#2E2414", "#C2A878", "#F2E8CF", "#FFFFFF"]), ("Electro", "COMMON", "LIGHTNING", "EL", ["#0B132B", "#1C2541", "#F2E205", "#5BC0BE"]),
            ("Vulture", "COMMON", "MASK", "VU", ["#132A13", "#31572C", "#90A955", "#ECF39E"]), ("Mysterio", "RARE", "STARS", "MY", ["#10002B", "#5A189A", "#C77DFF", "#E0AAFF"]),
            ("Kraven the Hunter", "COMMON", "MASK", "KH", ["#2B1D0E", "#7F5539", "#DDB892", "#EDE0D4"]), ("The Lizard", "COMMON", "MASK", "LZ", GREEN),
            ("Rhino", "COMMON", "MASK", "RH", ["#1F1F1F", "#6C757D", "#ADB5BD", "#F8F9FA"]), ("Scorpion", "COMMON", "MASK", "SC", ["#1B2E0B", "#606C38", "#DDA15E", "#FEFAE0"])]
city = [("Rooftop Swing", "COMMON", "CITY", "NY", NIGHT), ("Midnight Patrol", "COMMON", "MOON", "NY", NIGHT), ("Rainy Web Line", "COMMON", "WEB", "NY", BLUE),
        ("Daily Bugle Headline", "COMMON", "CITY", "DB", ["#1C1C1C", "#F8F9FA", "#D00000", "#FFBA08"]), ("Bridge Rescue", "RARE", "WAVE", "BR", BLUE),
        ("Neon Times Square", "RARE", "STARS", "TS", ["#0B090A", "#7209B7", "#F72585", "#4CC9F0"])]
classic = [("Web of Many Worlds", "LEGENDARY", "WEB", "MV", ["#03071E", "#370617", "#E85D04", "#FFBA08"]),
           ("Symbiote Night", "EPIC", "SPIDER", "SY", VENOM), ("Spider-Sense", "EPIC", "LIGHTNING", "SS", RED),
           ("Great Responsibility", "LEGENDARY", "HEART", "GR", ["#14080E", "#9D0208", "#F48C06", "#FFFFFF"])]

def sm_entries(lst, flavour):
    return [(n, r, m, g, p, f"Original alternate illustration inspired by {n} ({flavour}). Not an official trading card.") for (n, r, m, g, p) in lst]

spider = pack("spiderman", "Spider-Man", "Original illustrations inspired by Spider-Man, his allies and his rogues gallery.",
              "Fan tribute with original artwork. Not affiliated with or endorsed by Marvel or Sony. Not real trading cards.",
              "#E63946",
              [("heroes", "Heroes of New York", "Friendly neighbourhood heroes and the people who keep them going.", sm_entries(heroes, "hero")),
               ("rogues", "Rogues Gallery", "Villains of the city.", sm_entries(villains, "villain")),
               ("city", "City Nights", "Scenes over the skyline.", sm_entries(city, "city scene")),
               ("classics", "Web Classics", "Rare holographic designs.", sm_entries(classic, "special"))])

TURTLE = ["#0E1C10", "#3A7D44", "#9DC88D", "#F1F7ED"]
def t(colour): return ["#0E1C10", "#3A7D44", colour, "#F1F7ED"]
brothers = [("Leonardo", "RARE", "KATANA", "LEO", t("#2F80ED")), ("Raphael", "RARE", "SHELL", "RAPH", t("#EB5757")),
            ("Donatello", "RARE", "SHELL", "DON", t("#9B51E0")), ("Michelangelo", "RARE", "PIZZA", "MIKEY", t("#F2994A")),
            ("Leonardo — Training Day", "COMMON", "KATANA", "LEO", t("#2F80ED")), ("Raphael — Street Brawl", "COMMON", "SHELL", "RAPH", t("#EB5757")),
            ("Donatello — Lab Night", "COMMON", "LIGHTNING", "DON", t("#9B51E0")), ("Michelangelo — Skate Run", "COMMON", "WAVE", "MIKEY", t("#F2994A"))]
allies = [("Master Splinter", "EPIC", "LANTERN", "SPL", ["#2B1E16", "#7F5539", "#E6CCB2", "#FFFFFF"]), ("April O'Neil", "RARE", "CITY", "APR", ["#1E1E24", "#F4D35E", "#EE964B", "#FAF0CA"]),
          ("Casey Jones", "COMMON", "MASK", "CJ", ["#1A1A1A", "#4A4E69", "#C9ADA7", "#F2E9E4"]), ("Mondo Gecko", "COMMON", "WAVE", "MG", ["#102A12", "#5FAD56", "#F2C14E", "#FFFFFF"]),
          ("Leatherhead", "COMMON", "SEWER", "LH", ["#1B2A1B", "#4F6D3A", "#A3B18A", "#FFFFFF"]), ("Mona Lisa", "COMMON", "HEART", "ML", ["#1D1A2F", "#3F8C5A", "#F28482", "#FFFFFF"])]
foes = [("Shredder", "EPIC", "KATANA", "SHR", ["#120D0D", "#5C5C5C", "#C0C0C0", "#9D0208"]), ("Krang", "EPIC", "LIGHTNING", "KR", ["#1A0B1F", "#E85D9E", "#F9C6D9", "#FFFFFF"]),
        ("Bebop", "COMMON", "MASK", "BB", ["#1A1A1A", "#7F4F24", "#B5E48C", "#FFFFFF"]), ("Rocksteady", "COMMON", "MASK", "RS", ["#222222", "#6C757D", "#FFB703", "#FFFFFF"]),
        ("Baxter Stockman", "COMMON", "LIGHTNING", "BX", ["#0B132B", "#3A506B", "#5BC0BE", "#FFFFFF"]), ("Karai", "RARE", "KATANA", "KAR", ["#140B0B", "#7D1128", "#F3A712", "#FFFFFF"]),
        ("Foot Clan Ninja", "COMMON", "KATANA", "FT", ["#0D0D0D", "#3D3D3D", "#C1121F", "#F5F5F5"]), ("Tiger Claw", "RARE", "MASK", "TC", ["#1A1308", "#DD6E0F", "#F6BD60", "#FFFFFF"])]
nights = [("Pizza Rooftop", "COMMON", "PIZZA", "PZ", ["#1F0F0A", "#E76F51", "#F4A261", "#FFE8D6"]), ("Sewer Lair", "COMMON", "SEWER", "LR", TURTLE),
          ("Turtle Van Ride", "RARE", "CITY", "VAN", ["#132A13", "#4F772D", "#ECF39E", "#FFD166"]), ("Cowabunga Wave", "EPIC", "WAVE", "CWB", ["#03045E", "#0077B6", "#90E0EF", "#CAF0F8"]),
          ("Four Brothers", "LEGENDARY", "SHELL", "TMNT", ["#081C15", "#1B4332", "#52B788", "#FFD60A"]), ("Ninja Moonlight", "LEGENDARY", "MOON", "NIN", ["#03071E", "#1B263B", "#E0E1DD", "#80ED99"])]

def tm(lst, flavour):
    return [(n, r, m, g, p, f"Original alternate illustration inspired by {n} ({flavour}). Not an official trading card.") for (n, r, m, g, p) in lst]

tmnt = pack("tmnt", "Teenage Mutant Ninja Turtles", "Original illustrations inspired by the four brothers, their allies and their foes.",
            "Fan tribute with original artwork. Not affiliated with or endorsed by Paramount/Nickelodeon. Not real trading cards.",
            "#3A7D44",
            [("brothers", "Brothers in the Sewer", "Leo, Raph, Donnie and Mikey.", tm(brothers, "turtle")),
             ("allies", "Allies", "Friends above and below the streets.", tm(allies, "ally")),
             ("foes", "Foot Clan & Foes", "Rivals of the turtles.", tm(foes, "foe")),
             ("nights", "Pizza Nights", "Scenes from the city.", tm(nights, "scene"))])

os.makedirs(OUT, exist_ok=True)
for p in (bts, spider, tmnt):
    with open(os.path.join(OUT, p["collection"]["id"] + ".json"), "w") as f:
        json.dump(p, f, indent=1, ensure_ascii=False)
    from collections import Counter
    print(p["collection"]["id"], len(p["cards"]), dict(Counter(c["gameRarity"] for c in p["cards"])))
