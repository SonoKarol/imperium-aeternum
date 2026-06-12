// Enemy AI: corrupted legionaries patrolling the ruins.
// States: idle -> chase -> strafe/attack -> (stagger) -> dead. Respawn on rest.
import * as THREE from 'three';
import {
  buildHumanoid, resetPose, poseIdle, poseWalk, poseAttack, poseStagger, poseDeath,
} from './characters.js';
import { resolveStatics, angleDiff } from './world.js';

export const ENEMY_TYPES = {
  legionarius: {
    name: 'Legionarius Perditus',
    hp: 70, speed: 3.6, dmg: 16, reach: 2.2, aggro: 15, gloria: 35,
    poiseMax: 50, attackCd: 1.4, scale: 1,
    look: { tunic: 0x5a1414, armor: 0x787882, helmet: 'legionary', shield: true, skin: 0x9a7a5c },
  },
  praetorianus: {
    name: 'Praetorianus Umbrae',
    hp: 160, speed: 3.0, dmg: 26, reach: 2.5, aggro: 14, gloria: 110,
    poiseMax: 110, attackCd: 1.9, scale: 1.18,
    look: { tunic: 0x2a1a3a, armor: 0x3c3c46, helmet: 'centurion', shield: true, skin: 0x8a6a50, swordLen: 0.95 },
  },
};

let enemySeed = 7;
function erand() { enemySeed = (enemySeed * 16807) % 2147483647; return (enemySeed - 1) / 2147483646; }

export class Enemy {
  constructor(scene, world, fx, audio, type, spawnPos, patrolR = 6) {
    this.scene = scene; this.world = world; this.fx = fx; this.audio = audio;
    this.cfg = ENEMY_TYPES[type];
    this.type = type;
    this.spawn = spawnPos.clone();
    this.patrolR = patrolR;

    this.rig = buildHumanoid(Object.assign({ scale: this.cfg.scale }, this.cfg.look));
    scene.add(this.rig.root);

    this.pos = new THREE.Vector3();
    this.radius = 0.5 * this.cfg.scale;
    this.time = erand() * 10;
    this.walkCycle = 0;
    this.reset();
  }

  reset() {
    this.pos.copy(this.spawn);
    this.hp = this.cfg.hp;
    this.poise = 0;
    this.state = 'idle';
    this.stateT = 0;
    this.yaw = erand() * Math.PI * 2;
    this.attackCd = 0;
    this.waitT = 1 + erand() * 3;
    this.wanderTarget = null;
    this.strafeDir = 1;
    this.attackKind = 'light1';
    this.removed = false;
    this.justDied = false;
    this.rig.root.visible = true;
    for (const m of this.rig.meshes) { m.material.transparent = false; m.material.opacity = 1; }
    this.alertNearby = false;
  }

  get alive() { return this.state !== 'dead' && this.state !== 'gone'; }

  takeDamage(amount, poiseDmg, fromPos) {
    if (!this.alive) return;
    this.hp -= amount;
    this.poise += poiseDmg;
    this.aggro();
    if (this.hp <= 0) {
      this.state = 'dead';
      this.stateT = 0;
      this.justDied = true;
      this.audio.enemyDie();
      this.fx.soul(this.pos.clone().add(new THREE.Vector3(0, 1, 0)));
      return;
    }
    if (this.poise >= this.cfg.poiseMax) {
      this.poise = 0;
      this.state = 'stagger';
      this.stateT = 0;
    }
  }

  aggro() {
    if (this.state === 'idle') { this.state = 'chase'; this.stateT = 0; this.alertNearby = true; }
  }

  update(dt, player, allEnemies, fogLocked) {
    this.time += dt;
    this.stateT += dt;
    this.attackCd -= dt;
    const toPlayer = new THREE.Vector3().subVectors(player.pos, this.pos);
    const dist = Math.hypot(toPlayer.x, toPlayer.z);
    const playerTargetable = player.alive && player.action.name !== 'rest';

    let moving = 0;

    switch (this.state) {
      case 'idle': {
        if (playerTargetable && dist < this.cfg.aggro) { this.aggro(); break; }
        // lazy patrol around spawn
        this.waitT -= dt;
        if (this.waitT <= 0 && !this.wanderTarget) {
          const a = erand() * Math.PI * 2;
          this.wanderTarget = new THREE.Vector3(
            this.spawn.x + Math.cos(a) * this.patrolR * erand(), 0,
            this.spawn.z + Math.sin(a) * this.patrolR * erand());
        }
        if (this.wanderTarget) {
          const to = new THREE.Vector3().subVectors(this.wanderTarget, this.pos);
          if (Math.hypot(to.x, to.z) < 0.6) {
            this.wanderTarget = null;
            this.waitT = 2 + erand() * 4;
          } else {
            this.faceToward(to, dt, 4);
            this.stepForward(this.cfg.speed * 0.4, dt);
            moving = 0.4;
          }
        }
        break;
      }
      case 'chase': {
        if (!playerTargetable) { this.state = 'idle'; this.wanderTarget = null; break; }
        if (dist > this.cfg.aggro * 2.6 && this.stateT > 6) { this.state = 'idle'; break; }
        this.faceToward(toPlayer, dt, 7);
        if (dist > this.cfg.reach * 0.85) {
          this.stepForward(this.cfg.speed, dt);
          moving = 1;
        } else if (this.attackCd <= 0) {
          this.beginAttack();
        } else {
          this.state = 'strafe';
          this.stateT = 0;
          this.strafeDir = erand() < 0.5 ? 1 : -1;
        }
        break;
      }
      case 'strafe': {
        if (!playerTargetable) { this.state = 'idle'; break; }
        this.faceToward(toPlayer, dt, 7);
        const side = new THREE.Vector3(Math.cos(this.yaw), 0, -Math.sin(this.yaw)).multiplyScalar(this.strafeDir);
        this.pos.addScaledVector(side, this.cfg.speed * 0.45 * dt);
        if (dist > this.cfg.reach * 1.3) this.stepForward(this.cfg.speed * 0.6, dt);
        moving = 0.45;
        if (this.attackCd <= 0 && dist < this.cfg.reach * 1.1) this.beginAttack();
        else if (this.stateT > 1.4) { this.state = 'chase'; this.stateT = 0; }
        break;
      }
      case 'attack': {
        const ph = this.stateT / this.attackDur;
        if (ph > 0.15 && ph < 0.45) this.faceToward(toPlayer, dt, 3); // track during windup
        if (ph > this.hitA && ph < this.hitB) {
          this.stepForward(2.0, dt);
          if (!this.didHit && playerTargetable) {
            const ang = Math.abs(angleDiff(Math.atan2(toPlayer.x, toPlayer.z), this.yaw));
            if (dist < this.cfg.reach + player.radius && ang < 1.0) {
              this.didHit = true;
              if (player.takeDamage(this.cfg.dmg, this.pos)) this.audio.clang();
            }
          }
        }
        if (ph >= 1) {
          this.state = 'chase';
          this.stateT = 0;
          this.attackCd = this.cfg.attackCd * (0.8 + erand() * 0.5);
        }
        break;
      }
      case 'stagger': {
        if (this.stateT > 0.7) { this.state = 'chase'; this.stateT = 0; }
        break;
      }
      case 'dead': {
        if (this.stateT > 4) {
          // fade out the corpse
          const k = Math.min(1, (this.stateT - 4) / 1.5);
          for (const m of this.rig.meshes) { m.material.transparent = true; m.material.opacity = 1 - k; }
          if (k >= 1) { this.state = 'gone'; this.rig.root.visible = false; }
        }
        break;
      }
      case 'gone': return;
    }

    // pack alert: wake allies close to me
    if (this.alertNearby) {
      this.alertNearby = false;
      for (const e of allEnemies) {
        if (e !== this && e.alive && e.pos.distanceTo(this.pos) < 11) e.aggro();
      }
    }

    // separation from other enemies
    if (this.alive) {
      for (const e of allEnemies) {
        if (e === this || !e.alive) continue;
        const dx = this.pos.x - e.pos.x, dz = this.pos.z - e.pos.z;
        const d = Math.hypot(dx, dz), minD = this.radius + e.radius + 0.15;
        if (d < minD && d > 1e-4) {
          this.pos.x += (dx / d) * (minD - d) * 0.5;
          this.pos.z += (dz / d) * (minD - d) * 0.5;
        }
      }
      resolveStatics(this.pos, this.radius, this.world.colliders, fogLocked);
    }

    // animation
    if (moving > 0) this.walkCycle += dt * this.cfg.speed * (moving > 0.7 ? 1.8 : 1.1);
    this.updateRig(moving);
  }

  beginAttack() {
    this.state = 'attack';
    this.stateT = 0;
    this.didHit = false;
    // praetorians mix in heavy overheads
    this.attackKind = (this.type === 'praetorianus' && erand() < 0.4) ? 'heavy'
      : (erand() < 0.5 ? 'light1' : 'light2');
    this.attackDur = this.attackKind === 'heavy' ? 1.3 : 0.95;
    this.hitA = this.attackKind === 'heavy' ? 0.45 : 0.38;
    this.hitB = this.attackKind === 'heavy' ? 0.62 : 0.58;
    this.audio.swing();
  }

  faceToward(dirVec, dt, rate) {
    const target = Math.atan2(dirVec.x, dirVec.z);
    this.yaw += angleDiff(target, this.yaw) * Math.min(1, dt * rate);
  }

  stepForward(speed, dt) {
    this.pos.x += Math.sin(this.yaw) * speed * dt;
    this.pos.z += Math.cos(this.yaw) * speed * dt;
  }

  updateRig(moving) {
    resetPose(this.rig);
    switch (this.state) {
      case 'attack': poseAttack(this.rig, Math.min(1, this.stateT / this.attackDur), this.attackKind); break;
      case 'stagger': poseStagger(this.rig, this.stateT / 0.7); break;
      case 'dead': case 'gone': poseDeath(this.rig, Math.min(1, this.stateT / 1.0)); break;
      default:
        if (moving > 0) poseWalk(this.rig, this.walkCycle, moving);
        else poseIdle(this.rig, this.time);
    }
    this.rig.root.position.copy(this.pos);
    this.rig.root.rotation.y = this.yaw;
  }
}

// ---------------------------------------------------------------------------

export class EnemyManager {
  constructor(scene, world, fx, audio) {
    this.list = [];
    const defs = [
      // along the Via Sacra
      ['legionarius', -3, -6, 5],
      ['legionarius', 4, -22, 6],
      ['legionarius', -4, -38, 6],
      // eastern forum
      ['legionarius', 30, -20, 7],
      ['legionarius', 38, -26, 7],
      ['praetorianus', 34, -22, 5],
      // western temple guardian
      ['praetorianus', -38, -22, 6],
      // colosseum gate watch
      ['legionarius', -5, -52, 5],
      ['legionarius', 6, -54, 5],
    ];
    for (const [type, x, z, r] of defs) {
      this.list.push(new Enemy(scene, world, fx, audio, type, new THREE.Vector3(x, 0, z), r));
    }
    this.pendingGloria = 0;
  }

  resetAll() {
    for (const e of this.list) e.reset();
  }

  update(dt, player, fogLocked) {
    for (const e of this.list) {
      e.update(dt, player, this.list, fogLocked);
      if (e.justDied) {
        e.justDied = false;
        this.pendingGloria += e.cfg.gloria;
      }
    }
  }

  // collect gloria awarded since last call
  collectGloria() {
    const g = this.pendingGloria;
    this.pendingGloria = 0;
    return g;
  }

  aliveList() { return this.list.filter((e) => e.alive); }
}
