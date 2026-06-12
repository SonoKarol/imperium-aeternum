// IMPERIVM AETERNVM — a souls-like set in the ashes of the Roman Empire.
// Entry point: renderer, game loop and high-level state (shrine, death, boss).
import * as THREE from 'three';
import { World, SHRINE_POS, ARENA } from './world.js';
import { Input } from './input.js';
import { AudioFX } from './audio.js';
import { HUD } from './hud.js';
import { FX } from './fx.js';
import { Player } from './player.js';
import { EnemyManager } from './enemies.js';
import { Boss, BOSS_NAME } from './boss.js';

const SAVE_KEY = 'imperium-aeternum-save';

// ----------------------------------------------------------------- bootstrap
const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
document.body.appendChild(renderer.domElement);

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(58, window.innerWidth / window.innerHeight, 0.1, 600);

window.addEventListener('resize', () => {
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
});

const hud = new HUD();
const input = new Input(renderer.domElement);
const audio = new AudioFX();
const world = new World(scene);
const fx = new FX(scene);
const player = new Player(scene, world, input, audio, hud, fx);
const enemies = new EnemyManager(scene, world, fx, audio);
const boss = new Boss(scene, world, fx, audio);

// ----------------------------------------------------------------- save game
function saveGame() {
  localStorage.setItem(SAVE_KEY, JSON.stringify({
    vigor: player.vigor, endurance: player.endurance, strength: player.strength,
    gloria: player.gloria, bossDefeated: boss.defeated,
  }));
}
function loadGame() {
  try {
    const s = JSON.parse(localStorage.getItem(SAVE_KEY));
    if (!s) return;
    player.vigor = s.vigor ?? 8;
    player.endurance = s.endurance ?? 8;
    player.strength = s.strength ?? 8;
    player.gloria = s.gloria ?? 0;
    player.recompute();
    player.hp = player.maxHp;
    player.stamina = player.maxStamina;
    if (s.bossDefeated) { boss.defeated = true; boss.rig.root.visible = false; boss.state = 'gone'; }
  } catch { /* corrupt save: start fresh */ }
}
loadGame();

// ------------------------------------------------------------- gloria marker
// Where your gloria waits after death — a pillar of golden light.
let marker = null;
function dropGloria(pos, amount) {
  removeMarker();
  if (amount <= 0) return;
  const g = new THREE.Group();
  const beam = new THREE.Mesh(
    new THREE.CylinderGeometry(0.22, 0.32, 5, 12, 1, true),
    new THREE.MeshBasicMaterial({ color: 0xe8c050, transparent: true, opacity: 0.45, side: THREE.DoubleSide, depthWrite: false })
  );
  beam.position.y = 2.5;
  g.add(beam);
  const light = new THREE.PointLight(0xe8c050, 8, 10, 1.8);
  light.position.y = 1.5;
  g.add(light);
  g.position.copy(pos);
  scene.add(g);
  marker = { group: g, beam, amount };
}
function removeMarker() {
  if (marker) { scene.remove(marker.group); marker = null; }
}

// --------------------------------------------------------------- game states
let state = 'title';       // 'title' | 'playing'
let paused = false;
let resting = false;
let deadT = -1;
let bossFight = false;

const titleEl = document.getElementById('title');
const pausedEl = document.getElementById('paused');

titleEl.addEventListener('click', () => {
  audio.init();
  input.requestLock();
  hud.hideTitle();
  hud.show();
  state = 'playing';
  hud.message('Le ceneri di Roma ti attendono', '#d8d0c0', 3500, 'cerca il Sacrarium lungo la Via Sacra');
});

window.addEventListener('keydown', (e) => {
  if (state === 'title' && e.code === 'KeyN') {
    localStorage.removeItem(SAVE_KEY);
    player.vigor = player.endurance = player.strength = 8;
    player.gloria = 0;
    player.recompute();
    player.hp = player.maxHp;
    boss.defeated = false;
    boss.reset();
    document.querySelector('#title .wipe').textContent = '— salvataggio cancellato —';
  }
});

pausedEl.addEventListener('click', () => input.requestLock());
document.addEventListener('pointerlockchange', () => {
  if (state !== 'playing') return;
  paused = document.pointerLockElement !== renderer.domElement;
  hud.showPaused(paused && deadT < 0);
});

// ------------------------------------------------------------------ shrine
function atShrine() {
  return player.pos.distanceTo(SHRINE_POS) < 3.4;
}

function rest() {
  resting = true;
  player.startAction('rest');
  player.hp = player.maxHp;
  player.stamina = player.maxStamina;
  player.flasks = player.flasksMax;
  player.lockTarget = null;
  enemies.resetAll();
  if (!boss.defeated) boss.reset();
  audio.rest();
  hud.message('REQVIESCIS', '#c9a227', 2200, 'il fuoco sacro rinnova le tue forze');
  saveGame();
}

function standUp() {
  resting = false;
  player.startAction('free');
  hud.levelPanel(false);
}

function tryLevelUp(stat) {
  const cost = player.levelCost();
  if (player.gloria < cost) return;
  player.gloria -= cost;
  player[stat]++;
  player.recompute();
  player.hp = player.maxHp;
  player.stamina = player.maxStamina;
  audio.levelup();
  saveGame();
}

// ----------------------------------------------------------------- boss flow
function startBossFight() {
  bossFight = true;
  boss.awaken(player.pos);
  world.fogWall.visible = true;
  player.fogLocked = true;
  audio.startBossMusic();
  hud.message('CENTVRIO INVICTVS', '#d8d0c0', 3000, 'Custos Aeternus — l\'ultimo soldato di Roma');
}

function endBossFight(victory) {
  bossFight = false;
  world.fogWall.visible = false;
  player.fogLocked = false;
  audio.stopBossMusic();
  hud.boss(null);
  if (victory) {
    player.gloria += boss.gloria;
    audio.victory();
    hud.message('GLORIA AETERNA', '#e8c050', 6000, 'il Custode è caduto — l\'Impero riconosce il suo erede');
    saveGame();
  } else if (!boss.defeated) {
    boss.reset();
  }
}

// ------------------------------------------------------------------ death
function onPlayerDeath() {
  deadT = 0;
  hud.message('MORTVVS ES', '#a01818', 3200);
  dropGloria(player.pos.clone(), player.gloria);
  const lost = player.gloria;
  player.gloria = 0;
  if (lost > 0) marker.amount = lost;
  if (bossFight) endBossFight(false);
  hud.levelPanel(false);
  resting = false;
}

// -------------------------------------------------------------------- loop
const clock = new THREE.Clock();
let prevAlive = true;

function frame() {
  requestAnimationFrame(frame);
  const dt = Math.min(clock.getDelta(), 0.05);
  const time = clock.elapsedTime;

  world.update(dt, time);
  fx.update(dt);

  if (state === 'playing' && !paused) {
    // --- death / respawn ---
    if (!player.alive && prevAlive) onPlayerDeath();
    prevAlive = player.alive;
    if (deadT >= 0) {
      deadT += dt;
      if (deadT > 3.4) {
        deadT = -1;
        player.respawn(SHRINE_POS);
        enemies.resetAll();
        prevAlive = true;
      }
    }

    // --- targets the player can hit / lock onto ---
    const targets = enemies.aliveList();
    if (boss.active) targets.push(boss);

    if (!resting) player.update(dt, targets);
    else {
      input.consumeMouse();
      player.updateRig(dt);
      // level-up keys while resting
      if (input.pressed('Digit1')) tryLevelUp('vigor');
      if (input.pressed('Digit2')) tryLevelUp('endurance');
      if (input.pressed('Digit3')) tryLevelUp('strength');
      if (input.pressed('KeyE') || input.pressed('Space')) standUp();
      hud.levelPanel(true,
        { vigor: player.vigor, endurance: player.endurance, strength: player.strength },
        player.levelCost(), player.gloria >= player.levelCost());
    }

    enemies.update(dt, player, bossFight);
    boss.update(dt, player);

    // gloria earned from kills
    const earned = enemies.collectGloria();
    if (earned > 0) { player.gloria += earned; audio.pickup(); }
    if (boss.justDied) { boss.justDied = false; endBossFight(true); }

    // --- boss trigger: stepping into the arena ---
    if (!boss.defeated && !bossFight && player.alive) {
      const d = Math.hypot(player.pos.x - ARENA.x, player.pos.z - ARENA.z);
      if (d < ARENA.rIn - 3) startBossFight();
    }
    if (bossFight) hud.boss(BOSS_NAME, boss.hp / boss.maxHp);

    // --- interactions ---
    let prompt = null;
    if (player.alive && !resting) {
      if (marker && player.pos.distanceTo(marker.group.position) < 2.2) {
        prompt = '<b>[E]</b> recupera la gloria perduta';
        if (input.pressed('KeyE')) {
          player.gloria += marker.amount;
          fx.soul(marker.group.position.clone().add(new THREE.Vector3(0, 1, 0)));
          audio.pickup();
          removeMarker();
        }
      } else if (atShrine() && !bossFight) {
        prompt = '<b>[E]</b> riposa al Sacrarium';
        if (input.pressed('KeyE')) rest();
      }
    } else if (resting) {
      prompt = '<b>[1/2/3]</b> offri gloria — <b>[E]</b> alzati';
    }
    hud.setPrompt(prompt);

    // --- world systems ---
    world.updateSun(player.pos);
    player.updateCamera(camera, dt);

    // --- HUD ---
    hud.setVitals(player.hp, player.maxHp, player.stamina, player.maxStamina);
    hud.setFlasks(player.flasks, player.flasksMax);
    hud.setGloria(player.gloria);

    // lock-on reticle projection
    if (player.lockTarget && player.lockTarget.alive) {
      const v = player.lockTarget.pos.clone().add(new THREE.Vector3(0, 1.5, 0)).project(camera);
      if (v.z < 1) {
        hud.lockonAt({ x: (v.x * 0.5 + 0.5) * window.innerWidth, y: (-v.y * 0.5 + 0.5) * window.innerHeight });
      } else hud.lockonAt(null);
    } else hud.lockonAt(null);

    // marker shimmer
    if (marker) {
      marker.beam.material.opacity = 0.35 + 0.15 * Math.sin(time * 3);
      marker.group.rotation.y += dt * 0.8;
    }
  } else if (state === 'title') {
    // slow cinematic orbit around the shrine behind the title
    const a = time * 0.08;
    camera.position.set(SHRINE_POS.x + Math.sin(a) * 14, 5.5, SHRINE_POS.z + Math.cos(a) * 14);
    camera.lookAt(SHRINE_POS.x, 2, SHRINE_POS.z);
  }

  renderer.render(scene, camera);
  input.endFrame();
}

frame();

// debug handle (used by tests, harmless in production)
window.__game = { player, boss, enemies, world, renderer, scene, camera };
