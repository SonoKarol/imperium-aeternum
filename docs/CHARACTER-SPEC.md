# Roman legionary model — binding contract (Blender → glTF → jME)

The game animates characters by rotating **pivot nodes** (shoulders, hips, neck,
pelvis); each body part is a single rigid mesh hanging from its pivot. This file
defines the exact contract between the Blender build script and `Rig.java`.

## Output

- `src/main/resources/Models/legionary.glb` (glTF binary, +Y up — exporter default)
- Build script: `blender/build_legionary.py` (runs headless: `blender -b -P build_legionary.py`)
- Preview render for iteration: `blender/preview.py` renders `blender/preview.png`
  (Cycles, ~64 samples, 900×1100, three-quarter front view, neutral grey background,
  3-point lighting).

## Coordinate mapping

Model in Blender with **+Z up** and the soldier **facing −Y** (standard front
view). The glTF exporter (+Y up) then yields exactly what the game needs:
character facing **+Z**, up **+Y**. A pivot listed below as glTF `(x, y, z)`
is Blender `(x, −z, y)`.

## Parts — object names, pivots (glTF coords), extents

Every object's **origin must sit exactly at its pivot**. Apply rotation & scale
(not location) before export. Total budget ≤ 45k triangles. Subdivision/bevel
modifiers applied on export. Shade smooth (auto-smooth ~40°).

| Object   | Pivot (x, y, z)        | Geometry |
|----------|------------------------|----------|
| `Torso`  | (0, 0.95, 0)           | pelvis→neck (y 0.85..1.63): muscled cuirass with subtle pecs/abs relief, lorica shoulder bands, leather belt (cingulum) with hanging studded straps, red tunic visible at hem (y 0.85..0.62 skirt) and below the arms. Neck stub at top. |
| `Head`   | (0, 1.63, 0)           | neck + head (top ≈ 1.88): real human head silhouette — jaw, chin, nose, ears, brow; eyes can be simple darkened sockets; short hair cap. Most will be covered by the helmet. |
| `Helmet` | (0, 1.63, 0)           | imperial *galea*: skull dome, front brow ridge, flared neck guard at the back, two cheek guards (open face), small top knob. Fits over `Head` with ~1 cm clearance. |
| `Crest`  | (0, 1.63, 0)           | centurion crest: transverse arc of plume on a thin holder, sitting on the helmet dome. Separate object (toggled per character). |
| `ArmR`   | (0.33, 1.50, 0)        | shoulder ball→hand (wrist ≈ y 0.97): deltoid under a segmented shoulder guard (3 overlapping lames), bare upper arm, leather wrist bracer, open gripping hand (fingers can be a simple mitt with thumb). Hangs straight down, very slight natural bend. |
| `ArmL`   | (−0.33, 1.50, 0)       | mirror of ArmR. |
| `LegR`   | (0.14, 0.95, 0)        | hip→sole (y 0): thigh and calf with muscle silhouette, knee hint, *caliga* sandal with straps up the shin, toes hinted. |
| `LegL`   | (−0.14, 0.95, 0)       | mirror of LegR. |
| `Sword`  | (0.36, 0.93, 0.05)     | *gladius*: grip at origin, blade pointing **up (+Y)** 0.55 long, leaf-shaped tip, gold pommel + guard. The game rotates it forward at rest. |
| `Shield` | (−0.39, 1.10, 0.16)    | curved rectangular *scutum* ~0.55×0.85, curve radius ~0.45, central gold boss, painted wing/laurel emblem hinted with geometry or a second material, rim trim. Faces +Z (forward). |

The assembled figure must read as a **realistic Roman legionary**, ~1.88 m tall,
athletic proportions (head ≈ 1/7.5 of height). No visible primitive shapes:
everything organic/beveled. It will be inspected in close-up.

## Materials (Blender material names — the game re-tints by name)

| Name      | Principled BSDF |
|-----------|-----------------|
| `Skin`    | base (0.72, 0.54, 0.39), roughness 0.6 |
| `Tunic`   | base (0.55, 0.12, 0.12), roughness 0.9 (cloth) |
| `Armor`   | base (0.75, 0.76, 0.8), metallic 0.9, roughness 0.32 |
| `Iron`    | base (0.58, 0.59, 0.63), metallic 0.85, roughness 0.36 (helmet) |
| `Leather` | base (0.30, 0.20, 0.13), roughness 0.8 |
| `Gold`    | base (0.85, 0.65, 0.22), metallic 1.0, roughness 0.3 |
| `Crest`   | base (0.60, 0.10, 0.10), roughness 0.85 |
| `Wood`    | base (0.40, 0.26, 0.15), roughness 0.75 (shield back/grip if needed) |

No textures/UV maps required — flat PBR values only (the game lights everything
with HDRI + sun). Use material names EXACTLY as listed.

## Export settings (python)

```python
bpy.ops.export_scene.gltf(
    filepath=..., export_format='GLB',
    export_yup=True, export_apply=True,   # apply modifiers
    use_selection=False)
```

## How Rig.java consumes it

Loads the GLB once, deep-clones per character, finds the 10 objects by name,
re-parents each under its pivot node and zeroes its local translation. Per-variant
tinting overrides `BaseColor` on materials named `Tunic`, `Skin`, `Crest`;
marble statues replace every material. `Helmet`/`Crest`/`Shield` are optional
per character. So pivots and names above are load-bearing — do not rename.
