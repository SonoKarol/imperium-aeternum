# preview.py — renders front & back previews of the built scene.
# Run AFTER build_legionary.py in the same headless session:
#   blender -b --factory-startup -P build_legionary.py -P preview.py
import bpy, math
from math import radians, sin, cos
from mathutils import Vector

OUT_DIR = r"C:/Anello/imperium-aeternum/blender/"
SCENE = bpy.context.scene

# ---- neutral grey backdrop ----
if SCENE.world is None:
    SCENE.world = bpy.data.worlds.new('PreviewWorld')
SCENE.world.use_nodes = True
bg = SCENE.world.node_tree.nodes.get('Background')
if bg:
    bg.inputs[0].default_value = (0.30, 0.30, 0.32, 1.0)
    bg.inputs[1].default_value = 1.0

# ---- ground plane ----
bpy.ops.mesh.primitive_plane_add(size=20, location=(0, 0, 0))
plane = bpy.context.active_object
pmat = bpy.data.materials.new('Backdrop')
pmat.use_nodes = True
pbsdf = next(n for n in pmat.node_tree.nodes if n.type == 'BSDF_PRINCIPLED')
pbsdf.inputs['Base Color'].default_value = (0.22, 0.22, 0.24, 1.0)
pbsdf.inputs['Roughness'].default_value = 0.9
plane.data.materials.append(pmat)

TARGET = Vector((0.0, 0.0, 0.98))

# ---- 3-point lighting ----
def add_light(name, kind, loc, energy, size=3.0):
    d = bpy.data.lights.new(name, kind)
    d.energy = energy
    if kind == 'AREA':
        d.size = size
    ob = bpy.data.objects.new(name, d)
    ob.location = loc
    SCENE.collection.objects.link(ob)
    dirv = (TARGET - Vector(loc)).normalized()
    ob.rotation_euler = dirv.to_track_quat('-Z', 'Y').to_euler()
    return ob

add_light('Key', 'AREA', (3.2, -3.6, 3.4), 900, size=3.5)
add_light('Fill', 'AREA', (-3.8, -2.2, 2.2), 280, size=4.0)
add_light('Rim', 'AREA', (-1.2, 4.2, 3.6), 600, size=3.0)

# ---- camera ----
cam_data = bpy.data.cameras.new('Cam')
cam_data.lens = 60
cam = bpy.data.objects.new('Cam', cam_data)
SCENE.collection.objects.link(cam)
SCENE.camera = cam

# ---- render settings ----
SCENE.render.engine = 'CYCLES'
try:
    SCENE.cycles.device = 'CPU'
    SCENE.cycles.samples = 64
except Exception:
    pass
SCENE.render.resolution_x = 900
SCENE.render.resolution_y = 1100
SCENE.render.film_transparent = False
try:
    SCENE.view_settings.view_transform = 'Filmic'
except Exception:
    pass


def shoot(filename, angle_deg, dist=4.2, height=1.25):
    a = radians(angle_deg)
    cam.location = Vector((sin(a) * dist, -cos(a) * dist, height + 0.55))
    dirv = (TARGET - cam.location).normalized()
    cam.rotation_euler = dirv.to_track_quat('-Z', 'Y').to_euler()
    SCENE.render.filepath = OUT_DIR + filename
    bpy.ops.render.render(write_still=True)


shoot('preview_front.png', 28.0)
shoot('preview_back.png', 198.0)
print('Previews written to', OUT_DIR)
