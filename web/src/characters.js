// Procedural humanoid rigs built from primitives — no external models.
// Rig hierarchy:  root -> spinner(y=0.9) -> inner(y=-0.9) -> body/limbs
// `spinner` pivots at hip height so dodge-rolls read as real somersaults,
// while `inner` cancels the offset so pose code works in feet-space.
import * as THREE from 'three';

const smooth = (t) => t * t * (3 - 2 * t);
const clamp01 = (t) => Math.min(1, Math.max(0, t));
// Remap phase into [a,b] then smooth it.
const seg = (t, a, b) => smooth(clamp01((t - a) / (b - a)));

function mat(color, emissive = 0x000000) {
  return new THREE.MeshLambertMaterial({ color, emissive });
}

function box(w, h, d, material) {
  const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), material);
  m.castShadow = true;
  return m;
}

function cyl(rt, rb, h, material, seg = 10) {
  const m = new THREE.Mesh(new THREE.CylinderGeometry(rt, rb, h, seg), material);
  m.castShadow = true;
  return m;
}

export function buildHumanoid(opts = {}) {
  const o = Object.assign({
    skin: 0xb98a64, tunic: 0x8b2020, armor: 0x9a9aa6, trim: 0xc9a227,
    helmet: 'none',        // 'none' | 'legionary' | 'centurion'
    crest: 0xa01818,
    shield: false,
    swordLen: 0.8,
    scale: 1,
    marble: false,         // statue mode: single stone material, no shadows flicker
  }, opts);

  if (o.marble) {
    const stone = 0xc9c2b4;
    o.skin = stone; o.tunic = stone; o.armor = stone; o.trim = stone; o.crest = stone;
  }

  const mSkin = mat(o.skin), mTunic = mat(o.tunic), mArmor = mat(o.armor),
        mTrim = mat(o.trim), mCrest = mat(o.crest), mIron = mat(0x6e6e78),
        mLeather = mat(0x4a3220);

  const root = new THREE.Group();
  const spinner = new THREE.Group(); spinner.position.y = 0.9; root.add(spinner);
  const inner = new THREE.Group(); inner.position.y = -0.9; spinner.add(inner);

  // --- body (pelvis pivot) ---
  const body = new THREE.Group(); body.position.y = 0.95; inner.add(body);
  const torso = box(0.52, 0.62, 0.32, mArmor); torso.position.y = 0.31; body.add(torso);
  const skirt = box(0.5, 0.26, 0.3, mTunic); skirt.position.y = -0.08; body.add(skirt);
  const belt = box(0.54, 0.07, 0.34, mLeather); belt.position.y = 0.04; body.add(belt);
  // pteruges strips
  const strip = box(0.46, 0.14, 0.02, mLeather); strip.position.set(0, -0.22, 0.15); body.add(strip);

  // --- head ---
  const headG = new THREE.Group(); headG.position.y = 0.68; body.add(headG);
  const head = box(0.27, 0.3, 0.27, mSkin); head.position.y = 0.16; headG.add(head);
  if (o.helmet !== 'none') {
    const helm = cyl(0.17, 0.185, 0.22, mIron, 12); helm.position.y = 0.3; headG.add(helm);
    const brim = box(0.32, 0.04, 0.34, mIron); brim.position.set(0, 0.21, -0.02); headG.add(brim);
    if (o.helmet === 'centurion') {
      const crest = box(0.07, 0.16, 0.42, mCrest); crest.position.y = 0.46; headG.add(crest);
    }
  }

  // --- arms (shoulder pivots) ---
  function makeArm(side) {
    const g = new THREE.Group();
    g.position.set(0.33 * side, 0.55, 0);
    const pad = box(0.18, 0.14, 0.24, mArmor); pad.position.y = 0; g.add(pad);
    const arm = box(0.13, 0.56, 0.13, mSkin); arm.position.y = -0.32; g.add(arm);
    body.add(g);
    return g;
  }
  const rArm = makeArm(1), lArm = makeArm(-1);

  // --- sword in right hand ---
  const swordG = new THREE.Group();
  swordG.position.set(0, -0.58, 0);
  swordG.rotation.x = -Math.PI / 2;          // blade points forward at rest
  const grip = cyl(0.025, 0.025, 0.14, mLeather, 6); grip.position.y = -0.04; swordG.add(grip);
  const guard = box(0.16, 0.035, 0.05, mTrim); guard.position.y = 0.05; swordG.add(guard);
  const blade = box(0.07, o.swordLen, 0.02, mIron); blade.position.y = 0.05 + o.swordLen / 2; swordG.add(blade);
  const tip = box(0.05, 0.07, 0.02, mIron); tip.position.y = 0.05 + o.swordLen + 0.03; swordG.add(tip);
  rArm.add(swordG);

  // --- scutum on left arm ---
  let shieldG = null;
  if (o.shield) {
    shieldG = new THREE.Group();
    shieldG.position.set(-0.08, -0.42, 0.14);
    const sh = box(0.5, 0.72, 0.06, mTunic); shieldG.add(sh);
    const rim = box(0.54, 0.76, 0.02, mTrim); rim.position.z = -0.025; shieldG.add(rim);
    const bossKnob = cyl(0.07, 0.09, 0.06, mTrim, 8); bossKnob.rotation.x = Math.PI / 2; bossKnob.position.z = 0.05; shieldG.add(bossKnob);
    lArm.add(shieldG);
  }

  // --- legs (hip pivots) ---
  function makeLeg(side) {
    const g = new THREE.Group();
    g.position.set(0.14 * side, 0.95, 0);
    const leg = box(0.17, 0.88, 0.17, side === 1 ? mSkin : mSkin); leg.position.y = -0.49; g.add(leg);
    const sandal = box(0.18, 0.08, 0.26, mLeather); sandal.position.set(0, -0.9, 0.03); g.add(sandal);
    body.parent.add(g); // legs attach to inner, not body, so torso lean doesn't move them
    return g;
  }
  const rLeg = makeLeg(1), lLeg = makeLeg(-1);

  root.scale.setScalar(o.scale);

  const meshes = [];
  root.traverse((m) => { if (m.isMesh) meshes.push(m); });

  return {
    root, spinner, inner, body, headG, lArm, rArm, lLeg, rLeg, swordG, shieldG,
    meshes,
    height: 1.95 * o.scale,
  };
}

// ---------------------------------------------------------------------------
// Pose system: every frame the entity resets the rig then applies one or more
// pose functions. All functions are stateless w.r.t. the rig.
// ---------------------------------------------------------------------------

export function resetPose(rig) {
  rig.spinner.rotation.set(0, 0, 0);
  rig.inner.position.y = -0.9;
  rig.inner.rotation.set(0, 0, 0);
  rig.body.rotation.set(0, 0, 0);
  rig.body.position.y = 0.95;
  rig.headG.rotation.set(0, 0, 0);
  rig.lArm.rotation.set(0, 0, 0);
  rig.rArm.rotation.set(0, 0, 0);
  rig.lLeg.rotation.set(0, 0, 0);
  rig.rLeg.rotation.set(0, 0, 0);
}

export function poseIdle(rig, time) {
  const b = Math.sin(time * 1.7) * 0.012;
  rig.body.position.y = 0.95 + b;
  rig.lArm.rotation.x = 0.06 + Math.sin(time * 1.7) * 0.03;
  rig.rArm.rotation.x = 0.06 + Math.cos(time * 1.5) * 0.03;
  rig.rArm.rotation.z = -0.08;
  rig.lArm.rotation.z = 0.08;
}

// cycle advances with distance travelled; intensity 0..1 (walk -> run)
export function poseWalk(rig, cycle, intensity) {
  const a = 0.55 + intensity * 0.5;
  const s = Math.sin(cycle), c = Math.sin(cycle + Math.PI);
  rig.lLeg.rotation.x = s * a;
  rig.rLeg.rotation.x = c * a;
  rig.lArm.rotation.x = c * a * 0.7;
  rig.rArm.rotation.x = s * a * 0.7;
  rig.body.position.y = 0.95 + Math.abs(Math.cos(cycle)) * 0.05 * (0.5 + intensity);
  rig.body.rotation.x = 0.08 * intensity;
}

// kind: 'light1' | 'light2' | 'heavy'   phase: 0..1
export function poseAttack(rig, phase, kind) {
  if (kind === 'light1') {
    // diagonal slash from upper right
    const w = seg(phase, 0, 0.34), st = seg(phase, 0.34, 0.52), rec = seg(phase, 0.6, 1);
    rig.rArm.rotation.x = -2.3 * w + 3.1 * st - 0.8 * rec;
    rig.rArm.rotation.z = -0.7 * w + 1.0 * st - 0.3 * rec;
    rig.body.rotation.y = 0.5 * w - 1.0 * st + 0.5 * rec;
    rig.body.rotation.x = 0.25 * st - 0.25 * rec;
  } else if (kind === 'light2') {
    // backhand sweep
    const w = seg(phase, 0, 0.3), st = seg(phase, 0.3, 0.5), rec = seg(phase, 0.58, 1);
    rig.rArm.rotation.x = -1.1 * w + 1.6 * st - 0.5 * rec;
    rig.rArm.rotation.z = 1.3 * w - 2.2 * st + 0.9 * rec;
    rig.body.rotation.y = -0.6 * w + 1.2 * st - 0.6 * rec;
  } else { // heavy overhead smash
    const w = seg(phase, 0, 0.42), st = seg(phase, 0.42, 0.58), rec = seg(phase, 0.7, 1);
    rig.rArm.rotation.x = -2.9 * w + 3.9 * st - 1.0 * rec;
    rig.lArm.rotation.x = -1.4 * w + 1.8 * st - 0.4 * rec;
    rig.body.rotation.x = -0.25 * w + 0.65 * st - 0.4 * rec;
    rig.body.position.y = 0.95 - 0.12 * st + 0.12 * rec;
  }
}

export function poseRoll(rig, phase) {
  rig.spinner.rotation.x = smooth(phase) * Math.PI * 2;
  // tuck limbs
  rig.lLeg.rotation.x = 1.5; rig.rLeg.rotation.x = 1.3;
  rig.lArm.rotation.x = -1.1; rig.rArm.rotation.x = -1.1;
  rig.body.rotation.x = 0.5;
  rig.inner.position.y = -0.72; // tighten the ball
}

export function poseStagger(rig, phase) {
  const k = Math.sin(Math.PI * clamp01(phase));
  rig.body.rotation.x = -0.45 * k;
  rig.headG.rotation.x = -0.3 * k;
  rig.lArm.rotation.z = 0.5 * k;
  rig.rArm.rotation.z = -0.5 * k;
}

export function poseDeath(rig, phase) {
  const k = smooth(clamp01(phase));
  rig.spinner.rotation.x = -k * Math.PI / 2;   // fall on the back
  rig.inner.position.y = -0.9 + k * 0.25;
  rig.lArm.rotation.z = 0.9 * k;
  rig.rArm.rotation.z = -0.9 * k;
  rig.lLeg.rotation.x = 0.2 * k;
  rig.rLeg.rotation.x = -0.15 * k;
}

export function poseDrink(rig, phase) {
  const up = Math.sin(Math.PI * clamp01(phase));
  rig.lArm.rotation.x = -2.2 * up;
  rig.headG.rotation.x = -0.35 * up;
}

export function poseRest(rig) {
  // kneeling at the shrine
  rig.rLeg.rotation.x = -1.5;
  rig.lLeg.rotation.x = 1.0;
  rig.inner.position.y = -0.9 - 0.45 + 0.9; // sink — handled via body instead
  rig.inner.position.y = -1.35;
  rig.body.rotation.x = 0.25;
  rig.headG.rotation.x = 0.4;
  rig.rArm.rotation.x = 0.3;
  rig.lArm.rotation.x = 0.3;
}

// Boss-only poses ------------------------------------------------------------

export function poseBossCombo(rig, phase, step) {
  // three alternating wide slashes
  if (step === 0) return poseAttack(rig, phase, 'light1');
  if (step === 1) return poseAttack(rig, phase, 'light2');
  return poseAttack(rig, phase, 'heavy');
}

export function poseBossCharge(rig, phase) {
  if (phase < 0.3) { // windup: rear back, sword trailing
    const w = seg(phase, 0, 0.3);
    rig.body.rotation.x = -0.3 * w;
    rig.rArm.rotation.x = -1.8 * w;
    rig.rArm.rotation.z = -0.9 * w;
  } else { // lunge: lean forward hard, blade extended
    const k = seg(phase, 0.3, 0.45);
    rig.body.rotation.x = -0.3 + 0.75 * k;
    rig.rArm.rotation.x = -1.8 + 0.6 * k;
    rig.rArm.rotation.z = -0.9 + 0.9 * k;
    rig.lArm.rotation.x = 0.8 * k;
    const run = Math.sin(phase * 40) * 0.8;
    rig.lLeg.rotation.x = run;
    rig.rLeg.rotation.x = -run;
  }
}

export function poseBossSlam(rig, phase) {
  if (phase < 0.45) { // crouch + leap
    const w = seg(phase, 0, 0.2), air = seg(phase, 0.2, 0.45);
    rig.body.position.y = 0.95 - 0.25 * w + 0.1 * air;
    rig.lLeg.rotation.x = 0.9 * w;
    rig.rLeg.rotation.x = 0.9 * w;
    rig.rArm.rotation.x = -2.8 * (w + air) / 2;
    rig.lArm.rotation.x = -2.8 * (w + air) / 2;
  } else { // crash down
    const k = seg(phase, 0.45, 0.55);
    rig.rArm.rotation.x = -2.8 + 4.0 * k;
    rig.lArm.rotation.x = -2.8 + 4.0 * k;
    rig.body.rotation.x = 0.6 * k;
    rig.body.position.y = 0.95 - 0.3 * k;
  }
}
