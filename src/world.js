// Procedural environment: a dying-sun Roman ruinscape with a Via Sacra leading
// from the Sacrarium (checkpoint shrine) to a ruined colosseum (boss arena).
import * as THREE from 'three';
import { buildHumanoid, resetPose } from './characters.js';

// Deterministic PRNG so the world is identical on every run.
function mulberry32(a) {
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export const ARENA = { x: 0, z: -80, rIn: 22.5, rOut: 25.8, gateAngle: Math.PI / 2, gateHalf: 0.16 };
export const SHRINE_POS = new THREE.Vector3(0, 0, 14);
export const WORLD_RADIUS = 112;

function lam(color) { return new THREE.MeshLambertMaterial({ color }); }

export class World {
  constructor(scene) {
    this.scene = scene;
    this.colliders = [];   // {x, z, r} static circles
    this.fires = [];       // flickering point lights {light, base}
    this.time = 0;

    this.rand = mulberry32(1027);
    this.buildLighting();
    this.buildGround();
    this.buildRoad();
    this.buildShrine();
    this.buildColosseum();
    this.buildRuins();
    this.buildVegetation();
    this.buildFogWall();
  }

  addCollider(x, z, r) { this.colliders.push({ x, z, r }); }

  // ------------------------------------------------------------------ light
  buildLighting() {
    const s = this.scene;
    s.background = new THREE.Color(0x2b1d2e);
    s.fog = new THREE.FogExp2(0x4a2f3a, 0.011);

    s.add(new THREE.HemisphereLight(0x7a6a8a, 0x3a2f22, 1.05));

    const sun = new THREE.DirectionalLight(0xff9a4d, 1.8);
    sun.position.set(-40, 35, -60);
    sun.castShadow = true;
    sun.shadow.mapSize.set(2048, 2048);
    sun.shadow.camera.near = 1;
    sun.shadow.camera.far = 220;
    const ext = 55;
    sun.shadow.camera.left = -ext; sun.shadow.camera.right = ext;
    sun.shadow.camera.top = ext; sun.shadow.camera.bottom = -ext;
    sun.shadow.bias = -0.0008;
    s.add(sun);
    s.add(sun.target);
    this.sun = sun;

    // Dying sun disk on the horizon.
    const disk = new THREE.Mesh(
      new THREE.CircleGeometry(26, 32),
      new THREE.MeshBasicMaterial({ color: 0xff7733, fog: false })
    );
    disk.position.set(-220, 28, -330);
    disk.lookAt(0, 0, 0);
    s.add(disk);
    const halo = new THREE.Mesh(
      new THREE.CircleGeometry(48, 32),
      new THREE.MeshBasicMaterial({ color: 0x903a28, transparent: true, opacity: 0.5, fog: false })
    );
    halo.position.copy(disk.position).add(new THREE.Vector3(0, 0, -2).applyQuaternion(disk.quaternion));
    halo.quaternion.copy(disk.quaternion);
    s.add(halo);
  }

  // Shadow camera follows the player for crisp shadows everywhere.
  updateSun(playerPos) {
    this.sun.position.set(playerPos.x - 40, 35, playerPos.z - 60);
    this.sun.target.position.set(playerPos.x, 0, playerPos.z);
  }

  // ----------------------------------------------------------------- ground
  buildGround() {
    const geo = new THREE.PlaneGeometry(300, 300, 48, 48);
    geo.rotateX(-Math.PI / 2);
    // vertex-color mottling for a dry, scorched plain
    const colors = [];
    const c1 = new THREE.Color(0x5a4f3c), c2 = new THREE.Color(0x6b5a44), c3 = new THREE.Color(0x44392c);
    const pos = geo.attributes.position;
    for (let i = 0; i < pos.count; i++) {
      const t = this.rand();
      const c = t < 0.33 ? c1 : (t < 0.8 ? c2 : c3);
      colors.push(c.r, c.g, c.b);
    }
    geo.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3));
    const ground = new THREE.Mesh(geo, new THREE.MeshLambertMaterial({ vertexColors: true }));
    ground.receiveShadow = true;
    this.scene.add(ground);
  }

  // --------------------------------------------------------------- via sacra
  buildRoad() {
    const mStone = lam(0x7a7468);
    const slabGeo = new THREE.BoxGeometry(2.4, 0.12, 1.7);
    for (let z = 8; z > -53; z -= 1.9) {
      for (let i = -1; i <= 1; i++) {
        if (this.rand() < 0.12) continue; // missing slabs
        const slab = new THREE.Mesh(slabGeo, mStone);
        slab.position.set(i * 2.5 + (this.rand() - 0.5) * 0.3, 0.02 + this.rand() * 0.05, z + (this.rand() - 0.5) * 0.3);
        slab.rotation.y = (this.rand() - 0.5) * 0.1;
        slab.receiveShadow = true;
        this.scene.add(slab);
      }
    }
    // columns flanking the road
    for (let z = 0; z > -50; z -= 12) {
      this.addColumn(-6.5, z, 2 + this.rand() * 4);
      this.addColumn(6.5, z - 6, 2 + this.rand() * 4);
    }
    // statues greeting the pilgrim
    this.addStatue(-5.5, -24, 0.7);
    this.addStatue(5.5, -38, -0.7);
    this.addBrazier(-4, -12);
    this.addBrazier(4, -44);
  }

  addColumn(x, z, shaftH, tilt = 0) {
    const g = new THREE.Group();
    const mMarble = lam(0xb5ad9c);
    const base = new THREE.Mesh(new THREE.BoxGeometry(1.5, 0.4, 1.5), mMarble);
    base.position.y = 0.2; base.castShadow = base.receiveShadow = true; g.add(base);
    const shaft = new THREE.Mesh(new THREE.CylinderGeometry(0.42, 0.48, shaftH, 12), mMarble);
    shaft.position.y = 0.4 + shaftH / 2; shaft.castShadow = shaft.receiveShadow = true; g.add(shaft);
    if (shaftH > 4.5) { // intact column gets a capital
      const cap = new THREE.Mesh(new THREE.BoxGeometry(1.3, 0.35, 1.3), mMarble);
      cap.position.y = 0.4 + shaftH + 0.17; cap.castShadow = true; g.add(cap);
    }
    g.position.set(x, 0, z);
    g.rotation.z = tilt;
    this.scene.add(g);
    this.addCollider(x, z, 0.75);
    return g;
  }

  addStatue(x, z, faceYaw) {
    const rig = buildHumanoid({ marble: true, helmet: 'centurion', shield: true, scale: 1.25 });
    resetPose(rig);
    rig.rArm.rotation.x = -2.6; // saluting the dead empire
    rig.root.position.set(x, 0.9, z);
    rig.root.rotation.y = faceYaw;
    const ped = new THREE.Mesh(new THREE.BoxGeometry(1.6, 0.9, 1.6), lam(0x8f887a));
    ped.position.set(x, 0.45, z);
    ped.castShadow = ped.receiveShadow = true;
    this.scene.add(ped, rig.root);
    this.addCollider(x, z, 1.0);
  }

  addBrazier(x, z) {
    const g = new THREE.Group();
    const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.08, 0.12, 1.4, 8), lam(0x3a3a40));
    pole.position.y = 0.7; pole.castShadow = true; g.add(pole);
    const bowl = new THREE.Mesh(new THREE.CylinderGeometry(0.4, 0.2, 0.3, 10), lam(0x3a3a40));
    bowl.position.y = 1.5; bowl.castShadow = true; g.add(bowl);
    const flame = new THREE.Mesh(
      new THREE.ConeGeometry(0.26, 0.7, 8),
      new THREE.MeshBasicMaterial({ color: 0xff8c2a })
    );
    flame.position.y = 1.95; g.add(flame);
    const light = new THREE.PointLight(0xff7a2a, 12, 16, 1.8);
    light.position.y = 2; g.add(light);
    g.position.set(x, 0, z);
    this.scene.add(g);
    this.fires.push({ light, base: 12, flame });
    this.addCollider(x, z, 0.45);
  }

  // ----------------------------------------------------------------- shrine
  buildShrine() {
    const p = SHRINE_POS;
    const g = new THREE.Group();
    const dais = new THREE.Mesh(new THREE.CylinderGeometry(2.6, 2.9, 0.35, 24), lam(0x9a9286));
    dais.position.y = 0.17; dais.receiveShadow = true; g.add(dais);
    const ped = new THREE.Mesh(new THREE.CylinderGeometry(0.5, 0.65, 1.0, 10), lam(0x8f887a));
    ped.position.y = 0.85; ped.castShadow = true; g.add(ped);
    const bowl = new THREE.Mesh(new THREE.CylinderGeometry(0.75, 0.45, 0.4, 12), lam(0x4a4a52));
    bowl.position.y = 1.5; bowl.castShadow = true; g.add(bowl);

    const flame = new THREE.Mesh(
      new THREE.ConeGeometry(0.45, 1.2, 10),
      new THREE.MeshBasicMaterial({ color: 0xffb347 })
    );
    flame.position.y = 2.2; g.add(flame);
    this.shrineFlame = flame;

    const light = new THREE.PointLight(0xffa040, 26, 24, 1.7);
    light.position.y = 2.4; g.add(light);
    this.fires.push({ light, base: 26, flame });

    // rising embers
    const N = 40;
    const pts = new Float32Array(N * 3);
    this.emberData = [];
    for (let i = 0; i < N; i++) {
      this.emberData.push({ a: this.rand() * Math.PI * 2, r: this.rand() * 0.5, sp: 0.4 + this.rand() * 0.8, y: this.rand() * 3 });
    }
    this.emberGeo = new THREE.BufferGeometry();
    this.emberGeo.setAttribute('position', new THREE.BufferAttribute(pts, 3));
    const embers = new THREE.Points(this.emberGeo, new THREE.PointsMaterial({
      color: 0xffc060, size: 0.09, transparent: true, opacity: 0.9, depthWrite: false,
    }));
    embers.position.set(0, 1.8, 0);
    g.add(embers);

    // broken ring of mini-columns around the dais
    for (let a = 0; a < Math.PI * 2; a += Math.PI / 3) {
      if (this.rand() < 0.3) continue;
      const x = p.x + Math.cos(a) * 4.2, z = p.z + Math.sin(a) * 4.2;
      this.addColumn(x, z, 1.2 + this.rand() * 2.4);
    }

    g.position.copy(p);
    this.scene.add(g);
    this.addCollider(p.x, p.z, 1.1);
  }

  // -------------------------------------------------------------- colosseum
  buildColosseum() {
    const { x: cx, z: cz, rIn, rOut, gateAngle, gateHalf } = ARENA;
    const rMid = (rIn + rOut) / 2;
    const mTuff = lam(0x9c8f78);
    const mDark = lam(0x7c7260);
    const SEG = 18;

    // arena sand
    const sand = new THREE.Mesh(new THREE.CircleGeometry(rIn + 0.5, 40), lam(0x8a7350));
    sand.rotation.x = -Math.PI / 2;
    sand.position.set(cx, 0.06, cz);
    sand.receiveShadow = true;
    this.scene.add(sand);

    for (let i = 0; i < SEG; i++) {
      const a0 = (i / SEG) * Math.PI * 2;
      const aMid = a0 + Math.PI / SEG;
      const isGate = Math.abs(angleDiff(aMid, gateAngle)) < (Math.PI / SEG);
      const ruined = !isGate && this.rand() < 0.28;

      // pillar at each segment boundary
      const px = cx + Math.cos(a0) * rMid, pz = cz + Math.sin(a0) * rMid;
      const ph = ruined && this.rand() < 0.5 ? 3 + this.rand() * 3 : 9;
      const pillar = new THREE.Mesh(new THREE.BoxGeometry(1.6, ph, 1.6), mTuff);
      pillar.position.set(px, ph / 2, pz);
      pillar.rotation.y = -a0;
      pillar.castShadow = pillar.receiveShadow = true;
      this.scene.add(pillar);

      if (isGate) {
        // tall gate flanks + lintel
        const flank = new THREE.Mesh(new THREE.BoxGeometry(1.8, 11, 1.8), mDark);
        const a1 = a0 + (2 * Math.PI) / SEG;
        const fx = cx + Math.cos(a1) * rMid, fz = cz + Math.sin(a1) * rMid;
        flank.position.set(fx, 5.5, fz); flank.rotation.y = -a1;
        flank.castShadow = true; this.scene.add(flank);
        const lintel = new THREE.Mesh(new THREE.BoxGeometry(8.5, 1.6, 2), mDark);
        lintel.position.set(cx + Math.cos(aMid) * rMid, 10.2, cz + Math.sin(aMid) * rMid);
        lintel.rotation.y = -aMid + Math.PI / 2;
        lintel.castShadow = true; this.scene.add(lintel);
        continue;
      }

      // wall band between pillars (two tiers when intact)
      const arcLen = 2 * rMid * Math.sin(Math.PI / SEG) - 1.6;
      const wallH = ruined ? 2.5 + this.rand() * 2 : 8;
      const wall = new THREE.Mesh(new THREE.BoxGeometry(arcLen, wallH, 1.1), mTuff);
      wall.position.set(cx + Math.cos(aMid) * rMid, wallH / 2, cz + Math.sin(aMid) * rMid);
      wall.rotation.y = -aMid + Math.PI / 2;
      wall.castShadow = wall.receiveShadow = true;
      this.scene.add(wall);
      if (!ruined) {
        const upper = new THREE.Mesh(new THREE.BoxGeometry(arcLen, 3.2, 1.0), mDark);
        upper.position.set(cx + Math.cos(aMid) * rMid, 9 + 1.9, cz + Math.sin(aMid) * rMid);
        upper.rotation.y = -aMid + Math.PI / 2;
        upper.castShadow = true;
        this.scene.add(upper);
      } else {
        // rubble at the foot of broken sections
        for (let k = 0; k < 3; k++) {
          const rub = new THREE.Mesh(new THREE.DodecahedronGeometry(0.5 + this.rand() * 0.6), mDark);
          rub.position.set(
            cx + Math.cos(aMid) * (rMid + (this.rand() - 0.5) * 4),
            0.4, cz + Math.sin(aMid) * (rMid + (this.rand() - 0.5) * 4));
          rub.rotation.set(this.rand() * 3, this.rand() * 3, 0);
          rub.castShadow = true;
          this.scene.add(rub);
        }
      }
    }

    // arena torches
    this.addBrazier(cx - rIn + 3, cz);
    this.addBrazier(cx + rIn - 3, cz);
  }

  // The fog wall that seals the arena during the boss fight.
  buildFogWall() {
    const { x, z, rIn, gateAngle } = ARENA;
    const gx = x + Math.cos(gateAngle) * rIn, gz = z + Math.sin(gateAngle) * rIn;
    const wall = new THREE.Mesh(
      new THREE.PlaneGeometry(9, 10),
      new THREE.MeshBasicMaterial({ color: 0xd8cfa8, transparent: true, opacity: 0.22, side: THREE.DoubleSide, depthWrite: false })
    );
    wall.position.set(gx, 5, gz + 1.2);
    wall.visible = false;
    this.scene.add(wall);
    this.fogWall = wall;
  }

  // ------------------------------------------------------------------ ruins
  buildRuins() {
    // Eastern broken forum
    this.buildWallRect(34, -22, 14, 10, 0.4);
    for (let i = 0; i < 5; i++) {
      this.addColumn(28 + this.rand() * 14, -30 + this.rand() * 16, 1.5 + this.rand() * 4.5, (this.rand() - 0.5) * 0.12);
    }
    // Western temple
    this.buildTemple(-38, -30);
    // scattered lone columns and debris
    const spots = [[-20, 8], [22, 2], [-28, -8], [40, -45], [-15, -52], [18, -28], [-45, -5], [50, -15]];
    for (const [x, z] of spots) {
      if (this.rand() < 0.5) this.addColumn(x, z, 1 + this.rand() * 5, (this.rand() - 0.5) * 0.15);
      else this.addRock(x, z, 0.8 + this.rand() * 1.2);
    }
  }

  buildWallRect(cx, cz, w, d, brokenness) {
    const mWall = lam(0x8a7d66);
    const segments = [
      { x: cx, z: cz - d / 2, len: w, rot: 0 },
      { x: cx, z: cz + d / 2, len: w, rot: 0 },
      { x: cx - w / 2, z: cz, len: d, rot: Math.PI / 2 },
      { x: cx + w / 2, z: cz, len: d, rot: Math.PI / 2 },
    ];
    for (const s of segments) {
      const pieces = Math.ceil(s.len / 3);
      for (let i = 0; i < pieces; i++) {
        if (this.rand() < brokenness) continue;
        const h = 1.2 + this.rand() * 2.4;
        const off = -s.len / 2 + 1.5 + i * 3;
        const px = s.x + Math.cos(s.rot) * off;
        const pz = s.z + Math.sin(s.rot) * off;
        const wall = new THREE.Mesh(new THREE.BoxGeometry(3, h, 0.7), mWall);
        wall.position.set(px, h / 2, pz);
        wall.rotation.y = -s.rot;
        wall.castShadow = wall.receiveShadow = true;
        this.scene.add(wall);
        this.addCollider(px, pz, 1.4);
      }
    }
  }

  buildTemple(x, z) {
    const mMarble = lam(0xb5ad9c);
    // the podium is solid: block it off with overlapping circle colliders
    for (const ox of [-5.5, 0, 5.5]) this.addCollider(x + ox, z, 5.6);
    // podium of three steps
    for (let i = 0; i < 3; i++) {
      const stepW = 16 - i * 1.4, stepD = 11 - i * 1.4;
      const step = new THREE.Mesh(new THREE.BoxGeometry(stepW, 0.45, stepD), mMarble);
      step.position.set(x, 0.22 + i * 0.45, z);
      step.castShadow = step.receiveShadow = true;
      this.scene.add(step);
    }
    const floorY = 3 * 0.45;
    // colonnade (front row intact-ish, others broken)
    for (let i = 0; i < 5; i++) {
      const cxx = x - 6 + i * 3;
      const intact = this.rand() > 0.35;
      const col = this.addColumn(cxx, z + 4, intact ? 5 : 1 + this.rand() * 2.5);
      col.position.y = floorY;
    }
    // back wall + cult statue
    const back = new THREE.Mesh(new THREE.BoxGeometry(14, 5, 0.8), lam(0x8a7d66));
    back.position.set(x, floorY + 2.5, z - 4);
    back.castShadow = back.receiveShadow = true;
    this.scene.add(back);
    this.addCollider(x - 5, z - 4, 1.6); this.addCollider(x, z - 4, 1.6); this.addCollider(x + 5, z - 4, 1.6);
    const statue = buildHumanoid({ marble: true, scale: 1.7 });
    resetPose(statue);
    statue.rArm.rotation.x = -1.2; statue.lArm.rotation.x = -0.6;
    statue.root.position.set(x, floorY, z - 2.5);
    statue.root.rotation.y = Math.PI;
    this.scene.add(statue.root);
    // pediment
    const ped = new THREE.Mesh(new THREE.BoxGeometry(15, 1, 1.2), mMarble);
    ped.position.set(x, floorY + 5.6, z + 4);
    ped.castShadow = true;
    this.scene.add(ped);
  }

  addRock(x, z, s) {
    const rock = new THREE.Mesh(new THREE.DodecahedronGeometry(s), lam(0x6e6557));
    rock.position.set(x, s * 0.5, z);
    rock.rotation.set(this.rand() * 3, this.rand() * 3, 0);
    rock.castShadow = rock.receiveShadow = true;
    this.scene.add(rock);
    this.addCollider(x, z, s * 0.9);
  }

  // ------------------------------------------------------------- vegetation
  buildVegetation() {
    const mLeaf = lam(0x2e3a24), mDead = lam(0x4a3d2e);
    for (let i = 0; i < 46; i++) {
      const a = this.rand() * Math.PI * 2;
      const r = 45 + this.rand() * 60;
      const x = Math.cos(a) * r, z = Math.sin(a) * r;
      if (Math.hypot(x - ARENA.x, z - ARENA.z) < 32) continue;
      const g = new THREE.Group();
      const trunk = new THREE.Mesh(new THREE.CylinderGeometry(0.15, 0.25, 1.6, 6), mDead);
      trunk.position.y = 0.8; trunk.castShadow = true; g.add(trunk);
      if (this.rand() < 0.7) { // cypress
        const h = 4 + this.rand() * 3;
        const cone = new THREE.Mesh(new THREE.ConeGeometry(0.9, h, 7), mLeaf);
        cone.position.y = 1.4 + h / 2; cone.castShadow = true; g.add(cone);
      } else { // dead snag
        const b1 = new THREE.Mesh(new THREE.CylinderGeometry(0.06, 0.1, 1.4, 5), mDead);
        b1.position.set(0.3, 1.9, 0); b1.rotation.z = -0.7; b1.castShadow = true; g.add(b1);
        const b2 = new THREE.Mesh(new THREE.CylinderGeometry(0.05, 0.09, 1.1, 5), mDead);
        b2.position.set(-0.25, 1.7, 0.1); b2.rotation.z = 0.9; b2.castShadow = true; g.add(b2);
      }
      g.position.set(x, 0, z);
      this.scene.add(g);
      this.addCollider(x, z, 0.5);
    }
  }

  // ------------------------------------------------------------------ update
  update(dt, time) {
    this.time = time;
    for (const f of this.fires) {
      f.light.intensity = f.base * (0.85 + 0.3 * Math.sin(time * 11 + f.base) * Math.sin(time * 17));
      if (f.flame) {
        const s = 0.92 + 0.14 * Math.sin(time * 13 + f.base);
        f.flame.scale.set(s, 1 / s, s);
      }
    }
    // embers spiral upward
    if (this.emberGeo) {
      const pos = this.emberGeo.attributes.position.array;
      for (let i = 0; i < this.emberData.length; i++) {
        const e = this.emberData[i];
        e.y += e.sp * dt;
        if (e.y > 3) e.y = 0;
        const r = e.r * (1 - e.y / 4);
        pos[i * 3] = Math.cos(e.a + e.y * 2) * r;
        pos[i * 3 + 1] = e.y;
        pos[i * 3 + 2] = Math.sin(e.a + e.y * 2) * r;
      }
      this.emberGeo.attributes.position.needsUpdate = true;
    }
    if (this.fogWall && this.fogWall.visible) {
      this.fogWall.material.opacity = 0.18 + 0.08 * Math.sin(time * 2.2);
    }
  }
}

export function angleDiff(a, b) {
  let d = a - b;
  while (d > Math.PI) d -= Math.PI * 2;
  while (d < -Math.PI) d += Math.PI * 2;
  return d;
}

// Shared kinematic collision resolution for player and enemies.
// `fogLocked` = true while the boss fight seals the gate.
export function resolveStatics(pos, radius, colliders, fogLocked) {
  // circle obstacles
  for (const c of colliders) {
    const dx = pos.x - c.x, dz = pos.z - c.z;
    const d2 = dx * dx + dz * dz;
    const minD = c.r + radius;
    if (d2 < minD * minD && d2 > 1e-6) {
      const d = Math.sqrt(d2);
      pos.x = c.x + (dx / d) * minD;
      pos.z = c.z + (dz / d) * minD;
    }
  }
  // colosseum ring wall with a gate sector
  const { x, z, rIn, rOut, gateAngle, gateHalf } = ARENA;
  const dx = pos.x - x, dz = pos.z - z;
  const d = Math.hypot(dx, dz);
  const inBand = d > rIn - radius && d < rOut + radius;
  if (inBand && d > 1e-6) {
    const ang = Math.atan2(dz, dx);
    const inGate = Math.abs(angleDiff(ang, gateAngle)) < gateHalf;
    if (!inGate || fogLocked) {
      const target = d < (rIn + rOut) / 2 ? rIn - radius : rOut + radius;
      pos.x = x + (dx / d) * target;
      pos.z = z + (dz / d) * target;
    }
  }
  // outer world boundary
  const od = Math.hypot(pos.x, pos.z);
  if (od > WORLD_RADIUS) {
    pos.x *= WORLD_RADIUS / od;
    pos.z *= WORLD_RADIUS / od;
  }
}
