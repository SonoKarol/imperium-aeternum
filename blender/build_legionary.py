# build_legionary.py — builds the Roman legionary per docs/CHARACTER-SPEC.md
# Runs headless:  blender -b --factory-startup -P build_legionary.py
# Idempotent: wipes the scene first. Exports GLB to src/main/resources/Models/legionary.glb
import bpy, bmesh, math, os
from math import sin, cos, pi, radians, exp, sqrt
from mathutils import Vector, Matrix

OUT_GLB = r"C:/Anello/imperium-aeternum/src/main/resources/Models/legionary.glb"

# ---------------------------------------------------------------- scene reset
bpy.ops.wm.read_factory_settings(use_empty=True)
SCENE = bpy.context.scene

# ---------------------------------------------------------------- materials
MAT_DEFS = {
    'Skin':    ((0.72, 0.54, 0.39, 1.0), 0.0,  0.60),
    'Tunic':   ((0.55, 0.12, 0.12, 1.0), 0.0,  0.90),
    'Armor':   ((0.75, 0.76, 0.80, 1.0), 0.9,  0.32),
    'Iron':    ((0.58, 0.59, 0.63, 1.0), 0.85, 0.36),
    'Leather': ((0.30, 0.20, 0.13, 1.0), 0.0,  0.80),
    'Gold':    ((0.85, 0.65, 0.22, 1.0), 1.0,  0.30),
    'Crest':   ((0.60, 0.10, 0.10, 1.0), 0.0,  0.85),
    'Wood':    ((0.40, 0.26, 0.15, 1.0), 0.0,  0.75),
}
MATS = {}
for name, (col, met, rough) in MAT_DEFS.items():
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    bsdf = next(n for n in m.node_tree.nodes if n.type == 'BSDF_PRINCIPLED')
    bsdf.inputs['Base Color'].default_value = col
    bsdf.inputs['Metallic'].default_value = met
    bsdf.inputs['Roughness'].default_value = rough
    MATS[name] = m

# ---------------------------------------------------------------- contract pivots (Blender coords = (x, -z, y) of glTF)
PIVOTS = {
    'Torso':  (0.0,   0.0,   0.95),
    'Head':   (0.0,   0.0,   1.63),
    'Helmet': (0.0,   0.0,   1.63),
    'Crest':  (0.0,   0.0,   1.63),
    'ArmR':   (0.33,  0.0,   1.50),
    'ArmL':   (-0.33, 0.0,   1.50),
    'LegR':   (0.14,  0.0,   0.95),
    'LegL':   (-0.14, 0.0,   0.95),
    'Sword':  (0.36, -0.05,  0.93),
    'Shield': (-0.39, -0.16, 1.10),
}
PARTS = {k: [] for k in PIVOTS}

def add(part, ob):
    PARTS[part].append(ob)
    return ob

# ---------------------------------------------------------------- low-level helpers
def obj_from_bm(name, bm, matname, ss=0, solid=None):
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-6)
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    ob = bpy.data.objects.new(name, me)
    me.materials.append(MATS[matname])
    bpy.context.scene.collection.objects.link(ob)
    if solid is not None:
        mo = ob.modifiers.new('solid', 'SOLIDIFY')
        mo.thickness = solid
        mo.offset = 0.0
    if ss > 0:
        mo = ob.modifiers.new('ss', 'SUBSURF')
        mo.levels = ss
        mo.render_levels = ss
    return ob

def wrap_ang(a):
    while a > pi:  a -= 2 * pi
    while a < -pi: a += 2 * pi
    return a

def ellipse_ring(z, rx, ry, cx=0.0, cy=0.0, n=20, bumps=None, scallop=None):
    """Ring of n points; angle 0 faces front (-Y). bumps = [(center_deg, width_deg, amp)]."""
    pts = []
    for i in range(n):
        th = 2 * pi * i / n
        off = 0.0
        if bumps:
            for (bc, bw, ba) in bumps:
                d = wrap_ang(th - radians(bc))
                off += ba * exp(-(d / radians(bw)) ** 2)
        if scallop:
            off += scallop[1] * sin(scallop[0] * th)
        pts.append(Vector((cx + (rx + off) * sin(th), cy - (ry + off) * cos(th), z)))
    return pts

def loft_obj(name, matname, rings, cap_bottom=True, cap_top=True, close_loop=False, ss=0, solid=None, recalc=True):
    bm = bmesh.new()
    rows = []
    for ring in rings:
        rows.append([bm.verts.new(p) for p in ring])
    nr = len(rows)
    pairs = list(range(nr - 1)) + ([nr - 1] if close_loop else [])
    for ri in pairs:
        a_row = rows[ri]
        b_row = rows[(ri + 1) % nr]
        n = len(a_row)
        for i in range(n):
            bm.faces.new((a_row[i], a_row[(i + 1) % n], b_row[(i + 1) % n], b_row[i]))
    if not close_loop:
        if cap_bottom:
            row = rows[0]
            c = bm.verts.new(sum((v.co for v in row), Vector()) / len(row))
            for i in range(len(row)):
                bm.faces.new((row[(i + 1) % len(row)], row[i], c))
        if cap_top:
            row = rows[-1]
            c = bm.verts.new(sum((v.co for v in row), Vector()) / len(row))
            for i in range(len(row)):
                bm.faces.new((row[i], row[(i + 1) % len(row)], c))
    if recalc:
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return obj_from_bm(name, bm, matname, ss=ss, solid=solid)

def grid_obj(name, matname, fn, nu, nv, matkw=None, close_u=False, ss=0, solid=None):
    """fn(u, v)->Vector with u,v in [0,1]; if close_u, fn must be periodic in u."""
    bm = bmesh.new()
    cols = nu if close_u else nu + 1
    rows = []
    for i in range(cols):
        u = i / nu
        rows.append([bm.verts.new(fn(u, j / nv)) for j in range(nv + 1)])
    for i in range(nu):
        i2 = (i + 1) % cols
        for j in range(nv):
            bm.faces.new((rows[i][j], rows[i2][j], rows[i2][j + 1], rows[i][j + 1]))
    return obj_from_bm(name, bm, matname, ss=ss, solid=solid)

def sweep_obj(name, matname, frames, profile, ss=0, close_loop=False):
    """frames = [(origin, ux, uy)], profile = closed 2D pts."""
    rings = []
    for (o, ux, uy) in frames:
        rings.append([o + ux * px + uy * py for (px, py) in profile])
    return loft_obj(name, matname, rings, close_loop=close_loop, ss=ss)

def circle_prof(r, n=8, sx=1.0, sy=1.0):
    return [(r * sx * cos(2 * pi * i / n), r * sy * sin(2 * pi * i / n)) for i in range(n)]

def oriented_box(name, matname, center, half,
                 ax=Vector((1, 0, 0)), ay=Vector((0, 1, 0)), az=Vector((0, 0, 1)),
                 bevel=0.0, seg=2, ss=0):
    bm = bmesh.new()
    bmesh.ops.create_cube(bm, size=2.0)
    for v in bm.verts:
        v.co = Vector((v.co.x * half[0], v.co.y * half[1], v.co.z * half[2]))
    if bevel > 0:
        bmesh.ops.bevel(bm, geom=list(bm.edges), offset=bevel, segments=seg,
                        profile=0.5, affect='EDGES')
    M = Matrix((ax, ay, az)).transposed()
    cv = Vector(center)
    for v in bm.verts:
        v.co = cv + M @ v.co
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return obj_from_bm(name, bm, matname, ss=ss)

def sphere_obj(name, matname, center, radii, useg=12, vseg=8, ss=0):
    bm = bmesh.new()
    try:
        bmesh.ops.create_uvsphere(bm, u_segments=useg, v_segments=vseg, radius=1.0)
    except TypeError:
        bmesh.ops.create_uvsphere(bm, u_segments=useg, v_segments=vseg, diameter=1.0)
    cv = Vector(center)
    for v in bm.verts:
        v.co = cv + Vector((v.co.x * radii[0], v.co.y * radii[1], v.co.z * radii[2]))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    return obj_from_bm(name, bm, matname, ss=ss)

def band_around(name, matname, z, rx, ry, cx, cy, minor=0.0055, n=14, ss=1):
    """Thin leather band wrapped horizontally around a limb."""
    frames = []
    for i in range(n):
        th = 2 * pi * i / n
        o = Vector((cx + rx * sin(th), cy - ry * cos(th), z))
        ux = Vector((sin(th), -cos(th), 0.0))
        uy = Vector((0, 0, 1))
        frames.append((o, ux, uy))
    return sweep_obj(name, matname, frames, circle_prof(minor, 6), ss=ss, close_loop=True)

def band_xz(name, matname, y, rx, rz, cx, cz, minor=0.005, n=14, ss=1):
    """Thin band wrapped vertically (in XZ plane) e.g. around a foot."""
    frames = []
    for i in range(n):
        th = 2 * pi * i / n
        o = Vector((cx + rx * sin(th), y, cz + rz * cos(th)))
        ux = Vector((sin(th), 0.0, cos(th)))
        uy = Vector((0, 1, 0))
        frames.append((o, ux, uy))
    return sweep_obj(name, matname, frames, circle_prof(minor, 6), ss=ss, close_loop=True)

# ================================================================ TORSO
def build_torso():
    # muscled cuirass
    P = [(0, 5, -0.005)]                                  # sternum/linea alba dent
    AB = [(8, 7, 0.009), (-8, 7, 0.009), (0, 4, -0.006)]  # ab columns
    rings = [
        ellipse_ring(0.980, 0.180, 0.130),
        ellipse_ring(1.040, 0.168, 0.120, bumps=AB),
        ellipse_ring(1.100, 0.166, 0.118, bumps=AB),
        ellipse_ring(1.160, 0.172, 0.122, bumps=AB),
        ellipse_ring(1.220, 0.182, 0.128, bumps=AB),
        ellipse_ring(1.280, 0.198, 0.135, bumps=P),
        ellipse_ring(1.360, 0.225, 0.147, cy=-0.005,
                     bumps=[(20, 13, 0.017), (-20, 13, 0.017), (0, 6, -0.011)]),
        ellipse_ring(1.430, 0.252, 0.148, cy=-0.005,
                     bumps=[(20, 13, 0.012), (-20, 13, 0.012), (0, 6, -0.007)]),
        ellipse_ring(1.500, 0.280, 0.138),
        ellipse_ring(1.545, 0.255, 0.120),
        ellipse_ring(1.575, 0.150, 0.092),
        ellipse_ring(1.600, 0.085, 0.070),
    ]
    add('Torso', loft_obj('cuirass', 'Armor', rings, ss=2))

    # lorica shoulder bands (two stacked bands over upper chest)
    for k, (z0, z1, r0, r1) in enumerate([(1.455, 1.505, (0.262, 0.150), (0.285, 0.142)),
                                          (1.505, 1.553, (0.286, 0.140), (0.262, 0.124))]):
        rings = [ellipse_ring(z0, r0[0] + 0.008, r0[1] + 0.008),
                 ellipse_ring((z0 + z1) / 2, (r0[0] + r1[0]) / 2 + 0.011, (r0[1] + r1[1]) / 2 + 0.011),
                 ellipse_ring(z1, r1[0] + 0.008, r1[1] + 0.008)]
        add('Torso', loft_obj('chestband%d' % k, 'Armor', rings, ss=1))

    # neck stub (skin)
    add('Torso', loft_obj('neckstub', 'Skin', [
        ellipse_ring(1.550, 0.054, 0.056, n=12),
        ellipse_ring(1.610, 0.050, 0.053, n=12),
        ellipse_ring(1.660, 0.049, 0.052, n=12)], ss=1))

    # tunic skirt with pleats
    add('Torso', loft_obj('skirt', 'Tunic', [
        ellipse_ring(0.960, 0.176, 0.126, n=36),
        ellipse_ring(0.860, 0.182, 0.135, n=36, scallop=(9, 0.002)),
        ellipse_ring(0.740, 0.193, 0.148, n=36, scallop=(9, 0.005)),
        ellipse_ring(0.620, 0.205, 0.162, n=36, scallop=(9, 0.009))], ss=1))

    # cingulum belt
    add('Torso', loft_obj('belt', 'Leather', [
        ellipse_ring(0.905, 0.186, 0.136),
        ellipse_ring(0.955, 0.192, 0.142),
        ellipse_ring(1.005, 0.186, 0.136)], ss=1))
    add('Torso', oriented_box('buckle', 'Gold', (0, -0.144, 0.955), (0.030, 0.006, 0.030),
                              bevel=0.008, seg=2, ss=1))

    # hanging studded straps (front)
    for k, deg in enumerate([-24, -12, 0, 12, 24]):
        th = radians(deg)
        top = Vector(((0.190) * sin(th), -(0.140) * cos(th), 0.900))
        bot = Vector(((0.200) * sin(th), -(0.154) * cos(th), 0.735))
        mid = (top + bot) / 2
        axis_l = (bot - top).normalized()
        nrm = Vector((sin(th), -cos(th), 0.0))
        mid = mid + nrm * 0.010
        wid = axis_l.cross(nrm).normalized()
        add('Torso', oriented_box('strap%d' % k, 'Leather', mid, (0.015, 0.005, 0.086),
                                  ax=wid, ay=nrm, az=axis_l, bevel=0.0045, seg=2))
        for t in (-0.055, 0.0, 0.055):
            c = mid + axis_l * t + nrm * 0.006
            add('Torso', sphere_obj('stud%d_%d' % (k, int(t * 1000)), 'Gold', c,
                                    (0.0068, 0.0068, 0.0068), useg=8, vseg=5))

    # red tunic sleeves peeking below the shoulder armour
    for s in (1, -1):
        add('Torso', sphere_obj('sleeve%d' % s, 'Tunic', (s * 0.292, 0.0, 1.470),
                                (0.060, 0.082, 0.054), useg=12, vseg=8, ss=1))

# ================================================================ HEAD
def build_head():
    rings = [
        ellipse_ring(1.585, 0.054, 0.057, cy=0.010, n=20),
        ellipse_ring(1.625, 0.056, 0.060, cy=0.010, n=20),
        ellipse_ring(1.648, 0.063, 0.080, cy=-0.002, n=20, bumps=[(0, 7, 0.013)]),
        ellipse_ring(1.672, 0.068, 0.090, cy=-0.003, n=20, bumps=[(0, 6, 0.006)]),
        ellipse_ring(1.700, 0.072, 0.094, cy=-0.002, n=20, bumps=[(0, 5, 0.004)]),
        ellipse_ring(1.726, 0.0745, 0.0975, cy=-0.001, n=20,
                     bumps=[(33, 11, 0.0045), (-33, 11, 0.0045)]),
        ellipse_ring(1.752, 0.0755, 0.0980, cy=0.0, n=20,
                     bumps=[(20, 7, -0.0075), (-20, 7, -0.0075)]),
        ellipse_ring(1.778, 0.0740, 0.0995, cy=0.0, n=20,
                     bumps=[(15, 12, 0.0065), (-15, 12, 0.0065)]),
        ellipse_ring(1.806, 0.0705, 0.0940, cy=0.004, n=20),
        ellipse_ring(1.842, 0.0600, 0.0820, cy=0.008, n=20),
        ellipse_ring(1.872, 0.0380, 0.0540, cy=0.011, n=20),
    ]
    add('Head', loft_obj('skull', 'Skin', rings, ss=1))

    # nose (beveled wedge, tilted)
    a = radians(-14)
    add('Head', oriented_box('nose', 'Skin', (0, -0.108, 1.737), (0.0125, 0.013, 0.030),
                             ay=Vector((0, cos(a), sin(a))), az=Vector((0, -sin(a), cos(a))),
                             bevel=0.006, seg=2, ss=1))
    # ears
    for s in (1, -1):
        add('Head', sphere_obj('ear%d' % s, 'Skin', (s * 0.0775, 0.012, 1.742),
                               (0.009, 0.020, 0.027), useg=10, vseg=7, ss=0))
    # short hair cap
    add('Head', loft_obj('hair', 'Leather', [
        ellipse_ring(1.775, 0.0805, 0.106, cy=0.002, n=20),
        ellipse_ring(1.812, 0.0770, 0.1005, cy=0.004, n=20),
        ellipse_ring(1.848, 0.0665, 0.0885, cy=0.008, n=20),
        ellipse_ring(1.878, 0.0440, 0.0600, cy=0.011, n=20)], ss=1))

# ================================================================ HELMET
HC = Vector((0.0, 0.008, 1.772))   # helmet dome center
HR = 0.116

def rim_z(psi):
    return 1.700 + 0.092 * exp(-(wrap_ang(psi) / 0.80) ** 2)

def build_helmet():
    def dome(u, v):
        psi = 2 * pi * u
        zr = rim_z(psi)
        th_max = math.acos(max(-0.99, min(0.99, (zr - HC.z) / HR)))
        th = th_max * (1 - 0.965 * v)
        d = Vector((sin(th) * sin(psi), -sin(th) * cos(psi), cos(th)))
        return HC + d * HR
    add('Helmet', grid_obj('dome', 'Iron', dome, 24, 7, close_u=True, solid=0.005, ss=1))

    # brow ridge
    frames = []
    for i in range(13):
        psi = radians(-55 + 110 * i / 12)
        z = rim_z(psi) + 0.004
        rh = sqrt(max(1e-6, HR * HR - (z - HC.z) ** 2)) + 0.004
        o = Vector((rh * sin(psi), HC.y - rh * cos(psi), z))
        ux = Vector((sin(psi), -cos(psi), 0))
        frames.append((o, ux, Vector((0, 0, 1))))
    add('Helmet', sweep_obj('browridge', 'Iron', frames, circle_prof(0.0085, 8, sx=1.4), ss=1))

    # flared neck guard
    def nguard(u, v):
        psi = radians(110 + 140 * u)
        r = 0.114 + 0.088 * (v ** 1.15)
        z = 1.708 - 0.085 * v
        return Vector((r * sin(psi), HC.y - r * cos(psi), z))
    add('Helmet', grid_obj('neckguard', 'Iron', nguard, 12, 3, solid=0.006, ss=1))

    # cheek guards
    for s in (1, -1):
        def cheek(u, v, s=s):
            w = radians(26 - 12 * v)
            psic = s * radians(50 - 6 * v)
            psi = psic + (u - 0.5) * 2 * w * s
            r = 0.115 - 0.020 * v
            z = 1.718 - 0.105 * v
            return Vector((r * sin(psi), HC.y - r * cos(psi), z))
        add('Helmet', grid_obj('cheek%d' % s, 'Iron', cheek, 4, 4, solid=0.0055, ss=1))

    # top knob
    add('Helmet', loft_obj('knob', 'Iron', [
        ellipse_ring(1.882, 0.011, 0.011, cy=HC.y, n=10),
        ellipse_ring(1.896, 0.014, 0.014, cy=HC.y, n=10),
        ellipse_ring(1.906, 0.012, 0.012, cy=HC.y, n=10)], ss=1))

# ================================================================ CREST
def build_crest():
    CY = 0.012
    def plume(u, v):
        t = -1.33 + 2.66 * u
        R = 0.128 + v * (0.075 + 0.013 * sin(7.3 * t + 0.9))
        y = CY + (v * 0.004 * sin(9 * t))
        return Vector((R * sin(t), y, HC.z + R * cos(t)))
    add('Crest', grid_obj('plume', 'Crest', plume, 22, 3, solid=0.046, ss=1))
    frames = []
    for i in range(15):
        t = -1.37 + 2.74 * i / 14
        o = Vector((0.128 * sin(t), CY, HC.z + 0.128 * cos(t)))
        ux = Vector((sin(t), 0, cos(t)))
        frames.append((o, ux, Vector((0, 1, 0))))
    add('Crest', sweep_obj('cresthold', 'Gold', frames,
                           [(-0.006, -0.011), (0.006, -0.011), (0.006, 0.011), (-0.006, 0.011)], ss=1))

# ================================================================ ARM (right; left mirrored later)
def build_arm():
    X = 0.33
    rings = [
        ellipse_ring(1.555, 0.030, 0.030, cx=X),
        ellipse_ring(1.530, 0.056, 0.058, cx=X),
        ellipse_ring(1.490, 0.0655, 0.0675, cx=X),
        ellipse_ring(1.440, 0.0565, 0.0590, cx=X + 0.002),
        ellipse_ring(1.390, 0.0525, 0.0560, cx=X + 0.003, cy=-0.003, bumps=[(0, 18, 0.004)]),
        ellipse_ring(1.330, 0.0455, 0.0480, cx=X + 0.004),
        ellipse_ring(1.290, 0.0435, 0.0470, cx=X + 0.005, cy=0.004, bumps=[(180, 14, 0.005)]),
        ellipse_ring(1.230, 0.0470, 0.0505, cx=X + 0.007, cy=-0.006),
        ellipse_ring(1.150, 0.0415, 0.0445, cx=X + 0.009, cy=-0.012),
        ellipse_ring(1.060, 0.0335, 0.0350, cx=X + 0.011, cy=-0.018),
        ellipse_ring(1.005, 0.0305, 0.0315, cx=X + 0.012, cy=-0.020),
    ]
    add('ArmR', loft_obj('arm', 'Skin', rings, ss=1))

    # leather wrist bracer
    add('ArmR', loft_obj('bracer', 'Leather', [
        ellipse_ring(0.995, 0.0345, 0.0355, cx=X + 0.012, cy=-0.020, n=12),
        ellipse_ring(1.030, 0.0355, 0.0365, cx=X + 0.012, cy=-0.019, n=12),
        ellipse_ring(1.075, 0.0385, 0.0395, cx=X + 0.011, cy=-0.016, n=12)], ss=1))

    # hand mitt + thumb
    a = radians(-10)
    add('ArmR', oriented_box('hand', 'Skin', (X + 0.013, -0.030, 0.925), (0.029, 0.040, 0.054),
                             ay=Vector((0, cos(a), sin(a))), az=Vector((0, -sin(a), cos(a))),
                             bevel=0.012, seg=2, ss=1))
    td = Vector((0, -0.55, -0.84)).normalized()
    tw = Vector((1, 0, 0))
    add('ArmR', oriented_box('thumb', 'Skin', (X - 0.017, -0.052, 0.952), (0.010, 0.010, 0.024),
                             ax=tw, ay=td.cross(tw), az=td, bevel=0.005, seg=2, ss=1))

    # 3 segmented shoulder lames
    C = Vector((X, 0.0, 1.515))
    tilt = radians(25)
    ahat = Vector((sin(tilt), 0, cos(tilt)))
    uhat = Vector((cos(tilt), 0, -sin(tilt)))
    vhat = Vector((0, -1, 0))
    for i in range(3):
        R = 0.080 + 0.013 * i
        th0, th1 = 0.40 + 0.24 * i, 0.74 + 0.24 * i
        def lame(u, v, R=R, th0=th0, th1=th1):
            psi = -1.95 + 3.90 * u
            th = th0 + (th1 - th0) * v
            d = ahat * cos(th) + (uhat * cos(psi) + vhat * sin(psi)) * sin(th)
            return C + d * R
        add('ArmR', grid_obj('lame%d' % i, 'Armor', lame, 12, 2, solid=0.006, ss=1))

# ================================================================ LEG (right; left mirrored later)
def build_leg():
    X = 0.14
    rings = [
        ellipse_ring(0.970, 0.095, 0.105, cx=X),
        ellipse_ring(0.880, 0.090, 0.100, cx=X, cy=0.002),
        ellipse_ring(0.760, 0.079, 0.087, cx=X + 0.002, cy=-0.002),
        ellipse_ring(0.640, 0.068, 0.074, cx=X + 0.003, cy=-0.004),
        ellipse_ring(0.545, 0.0575, 0.0625, cx=X + 0.004, cy=-0.006, bumps=[(0, 16, 0.006)]),
        ellipse_ring(0.500, 0.0540, 0.0580, cx=X + 0.004, cy=-0.004, bumps=[(0, 12, 0.004)]),
        ellipse_ring(0.450, 0.0520, 0.0570, cx=X + 0.004, cy=0.002),
        ellipse_ring(0.385, 0.0560, 0.0630, cx=X + 0.004, cy=0.010, bumps=[(180, 30, 0.006)]),
        ellipse_ring(0.300, 0.0475, 0.0520, cx=X + 0.004, cy=0.010),
        ellipse_ring(0.200, 0.0365, 0.0385, cx=X + 0.004, cy=0.004),
        ellipse_ring(0.115, 0.0315, 0.0330, cx=X + 0.004),
        ellipse_ring(0.060, 0.0310, 0.0340, cx=X + 0.004),
    ]
    add('LegR', loft_obj('leg', 'Skin', rings, ss=1))

    # foot (tapered beveled box) + sole
    def foot(name, mat, half, cz, bevel, taper, ss):
        bm = bmesh.new()
        bmesh.ops.create_cube(bm, size=2.0)
        bmesh.ops.subdivide_edges(bm, edges=list(bm.edges), cuts=1, use_grid_fill=True)
        for v in bm.verts:
            co = Vector((v.co.x * half[0], v.co.y * half[1], v.co.z * half[2]))
            t = max(0.0, -co.y / half[1])
            co.x *= (1 - taper * t * t)
            co.z = -half[2] + (co.z + half[2]) * (1 - 0.40 * t * t)
            v.co = co
        bmesh.ops.bevel(bm, geom=list(bm.edges), offset=bevel, segments=2,
                        profile=0.5, affect='EDGES')
        cv = Vector((X + 0.004, -0.045, cz))
        for v in bm.verts:
            v.co = cv + v.co
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        return obj_from_bm(name, bm, mat, ss=ss)
    add('LegR', foot('foot', 'Skin', (0.044, 0.105, 0.034), 0.044, 0.012, 0.22, 1))
    add('LegR', foot('sole', 'Leather', (0.048, 0.112, 0.011), 0.011, 0.004, 0.22, 0))

    # caliga straps: shin bands + foot bands
    add('LegR', band_around('shinband0', 'Leather', 0.100, 0.0355, 0.0370, X + 0.004, 0.0))
    add('LegR', band_around('shinband1', 'Leather', 0.165, 0.0385, 0.0400, X + 0.004, 0.002))
    add('LegR', band_around('shinband2', 'Leather', 0.230, 0.0445, 0.0470, X + 0.004, 0.006))
    add('LegR', band_xz('footband0', 'Leather', -0.075, 0.043, 0.036, X + 0.004, 0.040))
    add('LegR', band_xz('footband1', 'Leather', -0.020, 0.046, 0.040, X + 0.004, 0.042))

# ================================================================ SWORD
def build_sword():
    P = Vector(PIVOTS['Sword'])
    add('Sword', sphere_obj('pommel', 'Gold', P + Vector((0, 0, -0.068)),
                            (0.026, 0.024, 0.022), useg=12, vseg=8, ss=0))
    # ribbed grip
    gr = []
    zs = [-0.052, -0.036, -0.020, -0.004, 0.012, 0.028, 0.048]
    rs = [0.0155, 0.019, 0.0145, 0.019, 0.0145, 0.019, 0.0160]
    for z, r in zip(zs, rs):
        gr.append(ellipse_ring(P.z + z, r, r, cx=P.x, cy=P.y, n=12))
    add('Sword', loft_obj('grip', 'Wood', gr, ss=1))
    add('Sword', oriented_box('guard', 'Gold', P + Vector((0, 0, 0.062)), (0.036, 0.018, 0.016),
                              bevel=0.010, seg=2, ss=1))
    # blade with midrib and leaf tip
    def blade_ring(z, w, t=0.005):
        pts = [(w, 0), (0.5 * w, 0.8 * t), (0, t), (-0.5 * w, 0.8 * t),
               (-w, 0), (-0.5 * w, -0.8 * t), (0, -t), (0.5 * w, -0.8 * t)]
        return [Vector((P.x + px, P.y + py, P.z + z)) for (px, py) in pts]
    rings = [blade_ring(0.074, 0.0315), blade_ring(0.200, 0.0305), blade_ring(0.340, 0.0295),
             blade_ring(0.460, 0.0315), blade_ring(0.550, 0.0260), blade_ring(0.600, 0.0160),
             blade_ring(0.632, 0.0050, 0.002)]
    add('Sword', loft_obj('blade', 'Iron', rings, ss=0))

# ================================================================ SHIELD
def build_shield():
    SCX, SCYC, R = -0.39, 0.29, 0.45   # curvature center (x, y), radius
    A = 0.611                           # half arc angle
    Z0, Z1 = 0.675, 1.525
    def shell(u, v):
        al = -A + 2 * A * u
        return Vector((SCX + R * sin(al), SCYC - R * cos(al), Z0 + (Z1 - Z0) * v))
    add('Shield', grid_obj('shell', 'Tunic', shell, 12, 10, solid=0.013, ss=1))

    # rim trim: top & bottom arcs + vertical edges
    rimprof = [(-0.012, -0.013), (0.012, -0.013), (0.012, 0.013), (-0.012, 0.013)]
    for name, z in (('rimtop', Z1 - 0.004), ('rimbot', Z0 + 0.004)):
        frames = []
        for i in range(13):
            al = -A + 2 * A * i / 12
            o = Vector((SCX + R * sin(al), SCYC - R * cos(al), z))
            ux = Vector((sin(al), -cos(al), 0))
            frames.append((o, ux, Vector((0, 0, 1))))
        add('Shield', sweep_obj(name, 'Gold', frames, rimprof, ss=1))
    for s in (1, -1):
        al = s * (A - 0.012)
        frames = []
        for z in (Z0, (Z0 + Z1) / 2, Z1):
            o = Vector((SCX + R * sin(al), SCYC - R * cos(al), z))
            ux = Vector((sin(al), -cos(al), 0))
            uy = Vector((cos(al), sin(al), 0))
            frames.append((o, ux, uy))
        add('Shield', sweep_obj('rimv%d' % s, 'Gold', frames,
                                [(-0.013, -0.011), (0.013, -0.011), (0.013, 0.011), (-0.013, 0.011)], ss=1))

    # central gold boss (plate + dome), built around -Y axis at shield center
    def yring(yy, r, n=16):
        return [Vector((SCX + r * sin(t), yy, 1.10 + r * cos(t)))
                for t in [2 * pi * i / n for i in range(n)]]
    add('Shield', loft_obj('boss', 'Gold', [
        yring(-0.158, 0.085), yring(-0.176, 0.085), yring(-0.180, 0.058),
        yring(-0.182, 0.050), yring(-0.198, 0.040), yring(-0.212, 0.020)], ss=1))

    # wood grip bar behind the boss
    add('Shield', oriented_box('grip', 'Wood', (SCX, -0.130, 1.10), (0.014, 0.013, 0.050),
                               bevel=0.004, seg=1))

    # emblem: gold wing strips fanning from the boss + lower bolts
    RE = R + 0.0095
    def place_strip(name, al, z, beta, hl, s):
        nrm = Vector((sin(al), -cos(al), 0))
        tng = Vector((cos(al), sin(al), 0))
        longx = (Vector((0, 0, 1)) * cos(beta) + tng * (s * sin(beta))).normalized()
        widx = longx.cross(nrm).normalized()
        o = Vector((SCX + RE * sin(al), SCYC - RE * cos(al), z))
        add('Shield', oriented_box(name, 'Gold', o, (0.0095, 0.0035, hl),
                                   ax=widx, ay=nrm, az=longx, bevel=0.0033, seg=1))
    for s in (1, -1):
        place_strip('wingA%d' % s, s * 0.16, 1.175, radians(18), 0.052, s)
        place_strip('wingB%d' % s, s * 0.265, 1.215, radians(40), 0.046, s)
        place_strip('wingC%d' % s, s * 0.345, 1.245, radians(63), 0.040, s)
        place_strip('boltA%d' % s, s * 0.14, 1.012, radians(-35), 0.050, s)

# ================================================================ build everything
build_torso()
build_head()
build_helmet()
build_crest()
build_arm()
build_leg()
build_sword()
build_shield()

# ---------------------------------------------------------------- bake modifiers
bpy.context.view_layer.update()
deps = bpy.context.evaluated_depsgraph_get()
for objs in PARTS.values():
    for ob in objs:
        if ob.modifiers:
            me = bpy.data.meshes.new_from_object(ob.evaluated_get(deps), depsgraph=deps)
            old = ob.data
            ob.modifiers.clear()
            ob.data = me
            bpy.data.meshes.remove(old)

# ---------------------------------------------------------------- merge components per part
def merge_objects(name, objs):
    bm = bmesh.new()
    slots = []
    for ob in objs:
        me = ob.data
        remap = []
        for m in me.materials:
            if m not in slots:
                slots.append(m)
            remap.append(slots.index(m))
        tmp = me.copy()
        if remap:
            for p in tmp.polygons:
                p.material_index = remap[min(p.material_index, len(remap) - 1)]
        bm.from_mesh(tmp)
        bpy.data.meshes.remove(tmp)
    final = bpy.data.meshes.new(name)
    bm.to_mesh(final)
    bm.free()
    for m in slots:
        final.materials.append(m)
    ob = bpy.data.objects.new(name, final)
    bpy.context.scene.collection.objects.link(ob)
    for o in objs:
        old = o.data
        bpy.data.objects.remove(o)
        bpy.data.meshes.remove(old)
    return ob

def finalize_smooth(ob, angle=40.0):
    me = ob.data
    me.polygons.foreach_set('use_smooth', [True] * len(me.polygons))
    try:
        me.set_sharp_from_angle(angle=radians(angle))
    except AttributeError:
        pass
    me.update()

finals = {}
for part in ('Torso', 'Head', 'Helmet', 'Crest', 'ArmR', 'LegR', 'Sword', 'Shield'):
    ob = merge_objects(part, PARTS[part])
    piv = Vector(PIVOTS[part])
    ob.data.transform(Matrix.Translation(-piv))
    ob.location = piv
    finalize_smooth(ob)
    finals[part] = ob

# mirrored parts
def mirror_part(src_name, dst_name):
    src = finals[src_name]
    me = src.data.copy()
    me.name = dst_name
    me.transform(Matrix.Diagonal((-1, 1, 1, 1)))
    me.flip_normals()
    ob = bpy.data.objects.new(dst_name, me)
    bpy.context.scene.collection.objects.link(ob)
    p = PIVOTS[dst_name]
    ob.location = Vector(p)
    finals[dst_name] = ob

mirror_part('ArmR', 'ArmL')
mirror_part('LegR', 'LegL')

# ---------------------------------------------------------------- stats
total = 0
print('--- triangle counts ---')
for name in PIVOTS:
    me = finals[name].data
    tris = sum(len(p.vertices) - 2 for p in me.polygons)
    total += tris
    print('%-8s %6d tris' % (name, tris))
print('TOTAL    %6d tris (budget 45000)' % total)
if total > 45000:
    print('WARNING: OVER TRIANGLE BUDGET')

# ---------------------------------------------------------------- export
os.makedirs(os.path.dirname(OUT_GLB), exist_ok=True)
try:
    bpy.ops.export_scene.gltf(filepath=OUT_GLB, export_format='GLB',
                              export_yup=True, export_apply=True, use_selection=False)
except TypeError:
    bpy.ops.export_scene.gltf(filepath=OUT_GLB, export_format='GLB',
                              export_apply=True, use_selection=False)
print('Exported', OUT_GLB)
