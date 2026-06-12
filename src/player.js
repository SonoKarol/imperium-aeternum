// Third-person souls-like character controller + camera.
import * as THREE from 'three';
import {
  buildHumanoid, resetPose, poseIdle, poseWalk, poseAttack, poseRoll,
  poseStagger, poseDeath, poseDrink, poseRest,
} from './characters.js';
import { resolveStatics, angleDiff } from './world.js';

const ATTACKS = {
  light1: { dur: 0.55, hitA: 0.34, hitB: 0.54, range: 2.4, arc: 1.25, dmgMul: 1.0, stam: 16, poise: 30, next: 'light2' },
  light2: { dur: 0.5,  hitA: 0.3,  hitB: 0.52, range: 2.4, arc: 1.4,  dmgMul: 1.1, stam: 16, poise: 30, next: 'light1' },
  heavy:  { dur: 0.95, hitA: 0.42, hitB: 0.6,  range: 2.6, arc: 1.1,  dmgMul: 2.1, stam: 32, poise: 80, next: null },
};

const ROLL = { dur: 0.62, speed: 8.6, iA: 0.04, iB: 0.62, stam: 20 };

export class Player {
  constructor(scene, world, input, audio, hud, fx) {
    this.scene = scene; this.world = world; this.input = input;
    this.audio = audio; this.hud = hud; this.fx = fx;

    this.rig = buildHumanoid({
      tunic: 0x8b2020, armor: 0xa8a8b4, helmet: 'legionary', shield: true, swordLen: 0.75,
    });
    scene.add(this.rig.root);

    this.pos = new THREE.Vector3(0, 0, 21);
    this.yaw = Math.PI;            // facing -Z, toward the road
    this.radius = 0.45;

    this.camYaw = Math.PI;
    this.camPitch = 0.28;
    this.camDist = 5.6;
    this.camPos = new THREE.Vector3();

    // stats
    this.vigor = 8; this.endurance = 8; this.strength = 8;
    this.gloria = 0;
    this.flasksMax = 4; this.flasks = 4;
    this.recompute();
    this.hp = this.maxHp;
    this.stamina = this.maxStamina;
    this.staminaDelay = 0;

    this.action = { name: 'free', t: 0 };
    this.comboQueued = false;
    this.lockTarget = null;
    this.walkCycle = 0;
    this.moveIntensity = 0;
    this.fogLocked = false;        // set by main during boss fight
    this.time = 0;
  }

  recompute() {
    this.maxHp = 40 + this.vigor * 13;
    this.maxStamina = 55 + this.endurance * 6;
    this.lightDmg = 12 + this.strength * 2.4;
  }

  get alive() { return this.action.name !== 'dead'; }
  get busy() { return this.action.name !== 'free'; }

  totalLevels() { return this.vigor + this.endurance + this.strength - 24; }
  levelCost() { return Math.floor(70 * Math.pow(1.22, this.totalLevels())); }

  spendStamina(v) { this.stamina -= v; this.staminaDelay = 0.7; }

  startAction(name, extra = {}) {
    this.action = Object.assign({ name, t: 0 }, extra);
  }

  // ------------------------------------------------------------------ update
  update(dt, targets) {
    this.time += dt;
    const inp = this.input;
    const a = this.action;
    a.t += dt;

    // camera orbit from mouse
    const md = inp.consumeMouse();
    if (!this.lockTarget) {
      this.camYaw -= md.x * 0.0026;
      this.camPitch = THREE.MathUtils.clamp(this.camPitch + md.y * 0.0022, -0.25, 1.25);
    }

    // lock-on toggle / validate
    if (inp.pressed('KeyQ')) this.toggleLock(targets);
    if (this.lockTarget && (!this.lockTarget.alive || this.lockTarget.pos.distanceTo(this.pos) > 34)) {
      this.lockTarget = null;
    }
    if (this.lockTarget) {
      const t = this.lockTarget.pos;
      const desired = Math.atan2(this.pos.x - t.x, this.pos.z - t.z);
      this.camYaw += angleDiff(desired, this.camYaw) * Math.min(1, dt * 5);
      this.camPitch += (0.3 - this.camPitch) * Math.min(1, dt * 3);
    }

    // stamina regen
    this.staminaDelay -= dt;
    if (this.staminaDelay <= 0 && a.name !== 'dead') {
      this.stamina = Math.min(this.maxStamina, this.stamina + (30 + this.endurance) * dt);
    }

    // movement input in camera space
    let mx = 0, mz = 0;
    if (inp.down('KeyW')) mz += 1;
    if (inp.down('KeyS')) mz -= 1;
    if (inp.down('KeyA')) mx -= 1;
    if (inp.down('KeyD')) mx += 1;
    const hasMove = (mx !== 0 || mz !== 0);
    const fwd = new THREE.Vector3(-Math.sin(this.camYaw), 0, -Math.cos(this.camYaw));
    const right = new THREE.Vector3(-fwd.z, 0, fwd.x);
    const moveDir = new THREE.Vector3();
    if (hasMove) moveDir.copy(fwd).multiplyScalar(mz).addScaledVector(right, mx).normalize();

    switch (a.name) {
      case 'free': this.updateFree(dt, moveDir, hasMove, targets); break;
      case 'roll': {
        const ph = a.t / ROLL.dur;
        this.moveAlong(a.dir, ROLL.speed * (1 - ph * 0.45), dt);
        if (ph >= 1) this.startAction('free');
        break;
      }
      case 'attack': this.updateAttack(dt, targets); break;
      case 'drink': {
        if (hasMove) this.moveAlong(moveDir, 1.2, dt);
        const ph = a.t / a.dur;
        if (ph > 0.55 && !a.healed) {
          a.healed = true;
          this.hp = Math.min(this.maxHp, this.hp + Math.round(this.maxHp * 0.6));
          this.audio.heal();
          this.fx.soul(this.pos.clone().add(new THREE.Vector3(0, 1.2, 0)));
        }
        if (ph >= 1) this.startAction('free');
        break;
      }
      case 'stagger':
        if (a.t >= a.dur) this.startAction('free');
        break;
      case 'dead':
      case 'rest':
        break;
    }

    resolveStatics(this.pos, this.radius, this.world.colliders, this.fogLocked);
    this.updateRig(dt);
  }

  updateFree(dt, moveDir, hasMove, targets) {
    const inp = this.input;

    // actions
    if (inp.pressed('Space') && this.stamina > 0) {
      const dir = hasMove ? moveDir.clone() : this.facing();
      this.spendStamina(ROLL.stam);
      this.startAction('roll', { dir });
      this.yaw = Math.atan2(dir.x, dir.z);
      this.audio.roll();
      return;
    }
    if (inp.buttonPressed(0) && this.stamina > 0) { this.beginAttack('light1'); return; }
    if (inp.buttonPressed(2) && this.stamina > 0) { this.beginAttack('heavy'); return; }
    if (inp.pressed('KeyF') && this.flasks > 0 && this.hp < this.maxHp) {
      this.flasks--;
      this.startAction('drink', { dur: 1.3, healed: false });
      return;
    }

    // locomotion
    const sprinting = (inp.down('ShiftLeft') || inp.down('ShiftRight')) && hasMove && this.stamina > 0;
    if (sprinting) this.spendStamina(11 * dt);
    const speed = sprinting ? 7.2 : 4.4;
    if (hasMove) {
      this.moveAlong(moveDir, speed, dt);
      const targetYaw = this.lockTarget && !sprinting
        ? Math.atan2(this.lockTarget.pos.x - this.pos.x, this.lockTarget.pos.z - this.pos.z)
        : Math.atan2(moveDir.x, moveDir.z);
      this.yaw += angleDiff(targetYaw, this.yaw) * Math.min(1, dt * 12);
      const prev = this.walkCycle;
      this.walkCycle += dt * speed * 1.65;
      this.moveIntensity = THREE.MathUtils.lerp(this.moveIntensity, sprinting ? 1 : 0.45, dt * 6);
      if (Math.floor(prev / Math.PI) !== Math.floor(this.walkCycle / Math.PI)) this.audio.step();
    } else {
      this.moveIntensity = THREE.MathUtils.lerp(this.moveIntensity, 0, dt * 8);
      if (this.lockTarget) {
        const t = Math.atan2(this.lockTarget.pos.x - this.pos.x, this.lockTarget.pos.z - this.pos.z);
        this.yaw += angleDiff(t, this.yaw) * Math.min(1, dt * 8);
      }
    }
  }

  beginAttack(kind) {
    const atk = ATTACKS[kind];
    this.spendStamina(atk.stam);
    this.startAction('attack', { kind, dur: atk.dur, hits: new Set() });
    this.comboQueued = false;
    if (this.lockTarget) {
      this.yaw = Math.atan2(this.lockTarget.pos.x - this.pos.x, this.lockTarget.pos.z - this.pos.z);
    }
    if (kind === 'heavy') this.audio.swingHeavy(); else this.audio.swing();
  }

  updateAttack(dt, targets) {
    const a = this.action;
    const atk = ATTACKS[a.kind];
    const ph = a.t / atk.dur;

    // queue combo
    if (this.input.buttonPressed(0) && atk.next && ph > 0.35) this.comboQueued = true;

    // step into the swing
    if (ph > atk.hitA && ph < atk.hitB) {
      this.moveAlong(this.facing(), 2.2, dt);
      // hit check
      for (const t of targets) {
        if (!t.alive || a.hits.has(t)) continue;
        const to = new THREE.Vector3().subVectors(t.pos, this.pos);
        const dist = Math.hypot(to.x, to.z);
        if (dist > atk.range + t.radius) continue;
        const ang = Math.abs(angleDiff(Math.atan2(to.x, to.z), this.yaw));
        if (ang > atk.arc) continue;
        a.hits.add(t);
        const dmg = Math.round(this.lightDmg * atk.dmgMul);
        t.takeDamage(dmg, atk.poise, this.pos);
        this.audio.hit();
        this.fx.blood(t.pos.clone().add(new THREE.Vector3(0, 1.3, 0)));
      }
    }

    // roll-cancel late in the swing
    if (ph > 0.7 && this.input.pressed('Space') && this.stamina > 0) {
      const dir = this.facing();
      this.spendStamina(ROLL.stam);
      this.startAction('roll', { dir });
      this.audio.roll();
      return;
    }

    if (ph >= 1) {
      if (this.comboQueued && atk.next && this.stamina > 0) this.beginAttack(atk.next);
      else this.startAction('free');
    }
  }

  moveAlong(dir, speed, dt) {
    this.pos.x += dir.x * speed * dt;
    this.pos.z += dir.z * speed * dt;
  }

  facing() { return new THREE.Vector3(Math.sin(this.yaw), 0, Math.cos(this.yaw)); }

  toggleLock(targets) {
    if (this.lockTarget) { this.lockTarget = null; return; }
    let best = null, bestScore = Infinity;
    for (const t of targets) {
      if (!t.alive) continue;
      const d = t.pos.distanceTo(this.pos);
      if (d > 28) continue;
      const ang = Math.abs(angleDiff(Math.atan2(t.pos.x - this.pos.x, t.pos.z - this.pos.z), this.camYaw + Math.PI));
      const score = d + ang * 6;
      if (score < bestScore) { bestScore = score; best = t; }
    }
    this.lockTarget = best;
  }

  // ------------------------------------------------------------------ damage
  takeDamage(amount, fromPos) {
    if (!this.alive) return false;
    if (this.action.name === 'roll') {
      const ph = this.action.t / ROLL.dur;
      if (ph > ROLL.iA && ph < ROLL.iB) return false;  // i-frames
    }
    if (this.action.name === 'rest') return false;
    this.hp -= amount;
    this.hud.hurtFlash();
    this.fx.blood(this.pos.clone().add(new THREE.Vector3(0, 1.3, 0)));
    if (this.hp <= 0) {
      this.hp = 0;
      this.startAction('dead');
      this.lockTarget = null;
      this.audio.death();
      return true;
    }
    this.audio.hurt();
    // small hits don't interrupt rolls/attacks past the strike, big ones stagger
    if (amount > this.maxHp * 0.18 && this.action.name === 'free') {
      this.startAction('stagger', { dur: 0.45 });
    }
    return true;
  }

  respawn(at) {
    this.pos.copy(at);
    this.pos.x += 1.8;
    this.hp = this.maxHp;
    this.stamina = this.maxStamina;
    this.flasks = this.flasksMax;
    this.startAction('free');
    this.lockTarget = null;
    this.yaw = Math.PI;
    this.camYaw = Math.PI;
  }

  // ------------------------------------------------------------------- visuals
  updateRig(dt) {
    const a = this.action;
    resetPose(this.rig);
    switch (a.name) {
      case 'free':
        if (this.moveIntensity > 0.03) poseWalk(this.rig, this.walkCycle, this.moveIntensity);
        else poseIdle(this.rig, this.time);
        break;
      case 'roll': poseRoll(this.rig, Math.min(1, a.t / ROLL.dur)); break;
      case 'attack': poseAttack(this.rig, Math.min(1, a.t / a.dur), a.kind); break;
      case 'drink': poseDrink(this.rig, Math.min(1, a.t / a.dur)); break;
      case 'stagger': poseStagger(this.rig, a.t / a.dur); break;
      case 'dead': poseDeath(this.rig, Math.min(1, a.t / 1.2)); break;
      case 'rest': poseRest(this.rig); break;
    }
    this.rig.root.position.copy(this.pos);
    this.rig.root.rotation.y = this.yaw;
  }

  updateCamera(camera, dt) {
    const target = new THREE.Vector3(this.pos.x, this.pos.y + 1.55, this.pos.z);
    const off = new THREE.Vector3(
      Math.sin(this.camYaw) * Math.cos(this.camPitch),
      Math.sin(this.camPitch),
      Math.cos(this.camYaw) * Math.cos(this.camPitch)
    ).multiplyScalar(this.camDist);
    const desired = target.clone().add(off);
    if (desired.y < 0.4) desired.y = 0.4;
    this.camPos.lerp(desired, Math.min(1, dt * 14));
    camera.position.copy(this.camPos);
    const look = target.clone();
    if (this.lockTarget) {
      look.lerp(this.lockTarget.pos.clone().setY(1.6), 0.35);
    }
    camera.lookAt(look);
  }
}
