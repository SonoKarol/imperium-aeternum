// CENTVRIO INVICTVS — the colosseum boss. Two phases:
//   >50% hp: 3-hit combos and a punishable charge
//   <50% hp: enrage — faster, adds a leaping slam with an expanding shockwave
import * as THREE from 'three';
import {
  buildHumanoid, resetPose, poseIdle, poseWalk, poseStagger, poseDeath,
  poseBossCombo, poseBossCharge, poseBossSlam,
} from './characters.js';
import { resolveStatics, angleDiff, ARENA } from './world.js';

export const BOSS_NAME = 'CENTVRIO INVICTVS, CVSTOS AETERNVS';

export class Boss {
  constructor(scene, world, fx, audio) {
    this.scene = scene; this.world = world; this.fx = fx; this.audio = audio;

    this.rig = buildHumanoid({
      scale: 1.85, tunic: 0x401010, armor: 0x32323e, trim: 0xc9a227,
      helmet: 'centurion', crest: 0xd01818, shield: false, swordLen: 1.25,
      skin: 0x7a5a44,
    });
    scene.add(this.rig.root);

    // ember eyes
    const eyeMat = new THREE.MeshBasicMaterial({ color: 0xff3300 });
    for (const sx of [-0.07, 0.07]) {
      const eye = new THREE.Mesh(new THREE.BoxGeometry(0.05, 0.03, 0.02), eyeMat);
      eye.position.set(sx, 0.16, 0.14);
      this.rig.headG.add(eye);
    }
    this.aura = new THREE.PointLight(0xff2200, 0, 14, 1.6);
    this.aura.position.y = 2.4;
    this.rig.root.add(this.aura);

    this.home = new THREE.Vector3(ARENA.x, 0, ARENA.z - 6);
    this.pos = new THREE.Vector3();
    this.radius = 1.0;
    this.maxHp = 950;
    this.gloria = 1800;
    this.dmgCombo = 30; this.dmgCharge = 46; this.dmgSlam = 52;
    this.time = 0;
    this.walkCycle = 0;
    this.defeated = false;   // persistent across rests once killed
    this.reset();
  }

  reset() {
    this.pos.copy(this.home);
    this.hp = this.maxHp;
    this.state = 'dormant';
    this.stateT = 0;
    this.yaw = 0;
    this.poiseAcc = 0;
    this.cd = 0;
    this.enraged = false;
    this.justDied = false;
    this.aura.intensity = 0;
    this.rig.root.visible = !this.defeated;
    for (const m of this.rig.meshes) { m.material.transparent = false; m.material.opacity = 1; }
  }

  get alive() { return !this.defeated && this.state !== 'dead' && this.state !== 'gone'; }
  get active() { return this.alive && this.state !== 'dormant'; }

  awaken(playerPos) {
    if (!this.alive || this.state !== 'dormant') return;
    this.state = 'shout';
    this.stateT = 0;
    this.faceInstant(playerPos);
    this.audio.bossRoar();
  }

  takeDamage(amount, poiseDmg, fromPos) {
    if (!this.alive || this.state === 'dormant') return;
    this.hp -= amount;
    this.poiseAcc += poiseDmg;
    if (this.hp <= 0) {
      this.hp = 0;
      this.state = 'dead';
      this.stateT = 0;
      this.justDied = true;
      this.defeated = true;
      this.audio.bossRoar();
      this.fx.soul(this.pos.clone().add(new THREE.Vector3(0, 2, 0)));
      return;
    }
    if (!this.enraged && this.hp < this.maxHp * 0.5) {
      this.enraged = true;
      this.state = 'shout';
      this.stateT = 0;
      this.audio.bossRoar();
      this.aura.intensity = 9;
    }
    // very high poise: only staggers after sustained pressure
    if (this.poiseAcc >= 220) {
      this.poiseAcc = 0;
      this.state = 'stagger';
      this.stateT = 0;
    }
  }

  faceInstant(p) { this.yaw = Math.atan2(p.x - this.pos.x, p.z - this.pos.z); }

  faceToward(p, dt, rate) {
    const t = Math.atan2(p.x - this.pos.x, p.z - this.pos.z);
    this.yaw += angleDiff(t, this.yaw) * Math.min(1, dt * rate);
  }

  stepForward(speed, dt) {
    this.pos.x += Math.sin(this.yaw) * speed * dt;
    this.pos.z += Math.cos(this.yaw) * speed * dt;
  }

  tryHitPlayer(player, reach, dmg, arc = 1.1) {
    if (!player.alive) return false;
    const to = new THREE.Vector3().subVectors(player.pos, this.pos);
    const d = Math.hypot(to.x, to.z);
    if (d > reach + player.radius) return false;
    const ang = Math.abs(angleDiff(Math.atan2(to.x, to.z), this.yaw));
    if (ang > arc) return false;
    return player.takeDamage(dmg, this.pos);
  }

  update(dt, player) {
    this.time += dt;
    this.stateT += dt;
    this.cd -= dt;
    if (this.defeated && this.state === 'gone') return;

    const speed = this.enraged ? 4.9 : 3.9;
    const toPlayer = new THREE.Vector3().subVectors(player.pos, this.pos);
    const dist = Math.hypot(toPlayer.x, toPlayer.z);
    let moving = 0;

    switch (this.state) {
      case 'dormant':
        break;
      case 'shout':
        if (this.stateT > 1.4) { this.state = 'chase'; this.stateT = 0; this.cd = 0.6; }
        break;
      case 'chase': {
        if (!player.alive) break;
        this.faceToward(player.pos, dt, 6);
        if (this.cd > 0) {
          // circle slowly while on cooldown
          if (dist < 5) {
            const side = new THREE.Vector3(Math.cos(this.yaw), 0, -Math.sin(this.yaw));
            this.pos.addScaledVector(side, speed * 0.3 * dt);
            moving = 0.35;
          } else { this.stepForward(speed * 0.7, dt); moving = 0.7; }
          break;
        }
        // pick an attack
        if (this.enraged && dist > 5 && dist < 14 && Math.random() < 0.4) {
          this.state = 'slam'; this.stateT = 0;
          this.slamTarget = player.pos.clone();
          this.didHit = false;
        } else if (dist > 7.5) {
          this.state = 'charge'; this.stateT = 0; this.didHit = false;
          this.audio.swingHeavy();
        } else if (dist < 4.5) {
          this.state = 'combo'; this.stateT = 0;
          this.comboStep = 0; this.didHit = false;
          this.audio.swing();
        } else { this.stepForward(speed, dt); moving = 1; }
        break;
      }
      case 'combo': {
        const stepDur = this.enraged ? 0.75 : 0.9;
        const ph = this.stateT / stepDur;
        if (ph < 0.3) this.faceToward(player.pos, dt, 5);
        if (ph > 0.36 && ph < 0.56) {
          this.stepForward(3.2, dt);
          if (!this.didHit && this.tryHitPlayer(player, 3.4, this.dmgCombo)) this.didHit = true;
          if (!this.didHit) { /* keep checking through the window */ }
        }
        if (ph >= 1) {
          this.comboStep++;
          this.didHit = false;
          this.stateT = 0;
          const maxSteps = this.enraged ? 3 : 2;
          if (this.comboStep > maxSteps || !player.alive) {
            this.state = 'chase';
            this.cd = this.enraged ? 0.8 : 1.5;
          } else this.audio.swing();
        }
        break;
      }
      case 'charge': {
        const ph = this.stateT;
        if (ph < 0.7) { // windup, track player
          this.faceToward(player.pos, dt, 4);
        } else if (ph < 1.6) { // dash
          this.stepForward(this.enraged ? 16 : 13.5, dt);
          moving = 1;
          if (!this.didHit && this.tryHitPlayer(player, 2.6, this.dmgCharge, 0.8)) this.didHit = true;
          // stop at arena wall
          const dHome = Math.hypot(this.pos.x - ARENA.x, this.pos.z - ARENA.z);
          if (dHome > ARENA.rIn - 2) { this.stateT = Math.max(this.stateT, 1.6); this.fx.dust(this.pos.clone()); }
        } else if (ph > 2.6) { // long recovery — the punish window
          this.state = 'chase'; this.stateT = 0; this.cd = this.enraged ? 0.7 : 1.3;
        }
        break;
      }
      case 'slam': {
        const DUR = 1.5;
        const ph = this.stateT / DUR;
        if (ph < 0.45) {
          // leap toward the marked position
          const k = Math.min(1, ph / 0.45);
          this.pos.x += (this.slamTarget.x - this.pos.x) * k * dt * 6;
          this.pos.z += (this.slamTarget.z - this.pos.z) * k * dt * 6;
          this.faceToward(this.slamTarget, dt, 8);
        } else if (!this.didHit && ph >= 0.5) {
          this.didHit = true;
          this.fx.shockwave(this.pos, 8, 0.55);
          this.fx.dust(this.pos.clone());
          this.audio.hit();
          this.shockT = 0;
        }
        if (this.didHit && this.shockT !== null) {
          this.shockT += dt;
          const r = 1 + (this.shockT / 0.55) * 8;
          const d = Math.hypot(player.pos.x - this.pos.x, player.pos.z - this.pos.z);
          if (Math.abs(d - r) < 1.1 && this.shockT < 0.55) {
            if (player.takeDamage(this.dmgSlam, this.pos)) this.shockT = null;
          }
        }
        if (ph >= 1) { this.state = 'chase'; this.stateT = 0; this.cd = 1.1; this.shockT = null; }
        break;
      }
      case 'stagger':
        if (this.stateT > 1.3) { this.state = 'chase'; this.stateT = 0; this.cd = 0.5; }
        break;
      case 'dead':
        if (this.stateT > 5) {
          const k = Math.min(1, (this.stateT - 5) / 2);
          for (const m of this.rig.meshes) { m.material.transparent = true; m.material.opacity = 1 - k; }
          this.aura.intensity = 0;
          if (k >= 1) { this.state = 'gone'; this.rig.root.visible = false; }
        }
        break;
    }

    if (this.alive && this.state !== 'dormant') {
      resolveStatics(this.pos, this.radius, this.world.colliders, false);
      // tether inside the arena
      const dHome = Math.hypot(this.pos.x - ARENA.x, this.pos.z - ARENA.z);
      if (dHome > ARENA.rIn - 1.2) {
        const k = (ARENA.rIn - 1.2) / dHome;
        this.pos.x = ARENA.x + (this.pos.x - ARENA.x) * k;
        this.pos.z = ARENA.z + (this.pos.z - ARENA.z) * k;
      }
    }

    if (this.enraged && this.alive) {
      this.aura.intensity = 7 + 3 * Math.sin(this.time * 9);
    }

    if (moving > 0) this.walkCycle += dt * speed * 1.5;
    this.updateRig(moving);
  }

  updateRig(moving) {
    resetPose(this.rig);
    switch (this.state) {
      case 'shout': {
        const k = Math.sin(Math.PI * Math.min(1, this.stateT / 1.4));
        this.rig.body.rotation.x = -0.35 * k;
        this.rig.headG.rotation.x = -0.4 * k;
        this.rig.rArm.rotation.x = -2.6 * k;
        this.rig.lArm.rotation.x = -1.2 * k;
        break;
      }
      case 'combo': {
        const stepDur = this.enraged ? 0.75 : 0.9;
        poseBossCombo(this.rig, Math.min(1, this.stateT / stepDur), this.comboStep % 3);
        break;
      }
      case 'charge': {
        const ph = Math.min(1, this.stateT / 2.6);
        poseBossCharge(this.rig, ph);
        break;
      }
      case 'slam': poseBossSlam(this.rig, Math.min(1, this.stateT / 1.5)); break;
      case 'stagger': poseStagger(this.rig, this.stateT / 1.3); break;
      case 'dead': case 'gone': poseDeath(this.rig, Math.min(1, this.stateT / 1.6)); break;
      case 'dormant': {
        // kneeling, waiting through the ages
        this.rig.rLeg.rotation.x = -1.5;
        this.rig.lLeg.rotation.x = 1.0;
        this.rig.inner.position.y = -1.35;
        this.rig.body.rotation.x = 0.3;
        this.rig.headG.rotation.x = 0.45;
        break;
      }
      default:
        if (moving > 0) poseWalk(this.rig, this.walkCycle, Math.min(1, moving));
        else poseIdle(this.rig, this.time);
    }
    this.rig.root.position.copy(this.pos);
    this.rig.root.rotation.y = this.yaw;
  }
}
