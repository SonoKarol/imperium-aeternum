# IMPERIVM AETERNVM — Java/jMonkeyEngine port specification

This document is the **binding contract** between modules. Every public
signature listed here must exist exactly as written — other modules compile
against them. Anything not listed is private implementation freedom.

The game is a port of the JavaScript prototype in `web/src/*.js`. **Read the
corresponding JS file before implementing your module** — all gameplay
constants, state machines and feel (timings, damage, ranges) must be carried
over 1:1 unless this spec overrides them.

## Stack and global rules

- jMonkeyEngine **3.7.0-stable**, package `aeternum`, Java 17 (`options.release=17`).
- All materials are PBR: use `ctx.tex` (TexLib — already implemented, see
  `TexLib.java`): `set("Grass004")` textured sets, `color(rgba, metallic, roughness)`,
  `glow(rgba, power)`. Bundled sets: `Grass004`, `Ground037` (dry cracked dirt),
  `Rock030` (cliff rock), `Marble012`, `PavingStones070`, `Bark007`.
- **Any geometry with a textured (normal-mapped) material needs tangents:**
  `com.jme3.util.TangentBinormalGenerator.generate(geom);` after creating it.
- Tile textures via `mesh.scaleTextureCoordinates(new Vector2f(u, v))`.
- Shadows: every world/character geometry sets
  `geom.setShadowMode(RenderQueue.ShadowMode.CastAndReceive)` (ground: `Receive`).
- Y axis is up. Entities live on the terrain: `y = ctx.world.getHeight(x, z)`.
- Do **not** run gradle, do not modify files owned by other modules, do not
  touch `Main.java`. Write only the files assigned to you.
- No external dependencies beyond jME + JDK. No physics engine — collisions
  are the circle-based system in `World.resolveStatics` (port of `web/src/world.js`).
- Already implemented, read them first: `C.java` (constants), `Combatant.java`,
  `GameCtx.java`, `TexLib.java`, `GameState.java` (skeleton — only read its fields).
- Code style: javadoc on public API, concise comments only where logic is
  non-obvious. No `System.out` logging in the final code.

### jME API cookbook (stick to these, do not invent methods)

- Shapes: `Box(hx,hy,hz)`, `Sphere(zSamples,radialSamples,radius)`,
  `Cylinder(axisSamples, radialSamples, radius, height, closed)` — note: jME
  cylinders are oriented along **Z**; rotate `geom.rotate(FastMath.HALF_PI,0,0)`
  to stand them up along Y. `Quad(w,h)`, `Dome`, `Torus(circleSamples,radialSamples,innerR,outerR)`.
- Custom meshes: `new Mesh()`, `mesh.setBuffer(Type.Position, 3, floatArray)`,
  `Type.Normal`, `Type.TexCoord` (2), `Type.Index` (3, intArray), then
  `mesh.updateBound()`.
- Node tree: `new Node("name")`, `node.attachChild(...)`,
  `node.setLocalTranslation(x,y,z)`, `node.setLocalRotation(new Quaternion().fromAngles(x,y,z))`,
  `node.setLocalScale(s)`, `node.rotate(x,y,z)`.
- Per-frame pose rotations: keep a `Quaternion tmp` and use
  `spatial.setLocalRotation(tmp.fromAngles(rx, ry, rz))`.
- Particles: `ParticleEmitter(name, ParticleMesh.Type.Triangle, numParticles)`;
  material `Common/MatDefs/Misc/Particle.j3md` with texture
  `Common/Textures/Effects/Explosion/flame.png` (`setTexture("Texture", ...)`);
  emitter API: `setStartColor/setEndColor/setStartSize/setEndSize/setLowLife/setHighLife/
  setGravity(x,y,z)/getParticleInfluencer().setInitialVelocity(v)/
  getParticleInfluencer().setVelocityVariation(f)/setParticlesPerSec(f)/emitAllParticles()`.
- Lights: `DirectionalLight`, `AmbientLight`, `PointLight` (`setRadius`). Add
  with `rootNode.addLight(...)`; a PointLight attached to moving nodes must be
  updated manually (`light.setPosition(...)`) each frame.
- GUI (Hud only): `BitmapFont font = assets.loadFont("Interface/Fonts/Default.fnt");
  BitmapText t = new BitmapText(font); t.setSize(f); t.setText(s); t.setColor(c);
  t.setLocalTranslation(x, y, 0); guiNode.attachChild(t);` Solid rectangles: `Quad`
  geometry with material `Common/MatDefs/Misc/Unshaded.j3md`, `setColor("Color", c)`,
  with `mat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha)` for
  transparency, attached to guiNode (screen pixels, origin bottom-left).
  Screen size: `ctx.cam.getWidth()/getHeight()`.
- Input (PlayerCtrl only): `ctx.input.addMapping("W", new KeyTrigger(KeyInput.KEY_W));
  ctx.input.addListener(actionListener, "W", ...);` Mouse:
  `new MouseAxisTrigger(MouseInput.AXIS_X, true/false)` (analog),
  `new MouseButtonTrigger(MouseInput.BUTTON_LEFT)`.
  Hide cursor: `ctx.input.setCursorVisible(false)`.
- Audio: **do not use jME audio.** `SynthAudio` uses `javax.sound.sampled` only.

## Architecture

`Main` (integration, NOT yours) creates: World → SynthAudio → Fx → Hud →
PlayerCtrl → EnemyManager → Boss → GameState, then each frame calls, in order:
`player.update(tpf)`, `enemies.update(tpf)`, `boss.update(tpf)`,
`game.update(tpf, ctx)`, `fx.update(tpf)`, `world.update(tpf)`, `hud.update(tpf)`.
Every constructor takes `(GameCtx ctx)` and keeps the reference; `ctx` fields
are all assigned before the first update tick.

The gameplay layout, enemy placement, damage numbers, stamina costs, AI state
machines, boss phases and shrine/death rules are **identical to the JS
version** — port them faithfully.

---

## Module A — `World.java` (port of `web/src/world.js`)

```java
public class World {
    public final java.util.List<float[]> colliders = new java.util.ArrayList<>(); // {x, z, r}
    public com.jme3.scene.Spatial fogWall;     // arena gate seal, hidden by default
    public com.jme3.math.Vector3f shrinePos;   // C.SHRINE with terrain height applied
    public com.jme3.light.DirectionalLight sun;

    public World(GameCtx ctx)                  // builds everything, attaches to ctx.rootNode
    public float getHeight(float x, float z)   // analytic terrain height
    public void resolveStatics(com.jme3.math.Vector3f pos, float radius, boolean fogLocked)
    public void update(float tpf)              // fire flicker, fog wall pulse
}
```

- **Terrain**: custom grid mesh ~240×240 m, 1.5 m step, heights from a smooth
  analytic function (sum of 3–4 sines, amplitude ≤ 2.5): flat (±0.3) inside the
  gameplay corridor (shrine→arena, the via, forum/temple areas, arena interior),
  rolling hills towards the rim, and a smooth depression to −3.5 m inside
  `C.LAKE_R` of `(C.LAKE_X, C.LAKE_Z)` so the lake (water plane added by Main at
  `C.WATER_LEVEL`) has shores. Material `set("Grass004")` tiled ~48×48, with a
  second pass of `Ground037` decal discs (slightly raised flat discs, radius
  6–10) scattered along the via and at the arena for visual variety. Arena
  floor: flat `Ground037` disc radius `ARENA_R_IN+0.5` at the arena center.
  Compute smooth normals for the grid mesh.
- **Via Sacra**: paving slabs (`PavingStones070` boxes ~2.4×0.12×1.7 m, slight
  jitter/rotation, a few missing) from z≈8 to z≈−53, three columns wide,
  following terrain height.
- **Colonnades, broken columns** flanking the via; columns = marble cylinders
  (radialSamples ≥ 20) + base/capital boxes; some broken/tilted. Each column
  adds a collider `{x, z, 0.75f}`.
- **Statues**: 2 marble figures on pedestals along the via — build them with
  `Rig.humanoid(...)` marble option, posed saluting (see world.js), static.
- **Braziers** along the via and 2 in the arena: iron bowl + `Fx.attachFire(node, small)`
  (see Module Fx) + PointLight (flickered in `update`).
- **Shrine** at `C.SHRINE`: stone dais (collider), pedestal, fire bowl with
  `Fx.attachFire(node, large)`, ring of broken mini-columns.
- **Colosseum** at the arena constants: 18 segments, ring of pillars + wall
  bands + upper tier (rock material `Rock030`, marble trim), gate towards +Z
  with tall flanks and lintel, ~28% ruined segments with rubble
  (`Rock030` dodecahedron-ish spheres). The ring band collision is handled in
  `resolveStatics` exactly like world.js (band rIn..rOut, gate sector passable
  unless `fogLocked`).
- **Western temple** near the lake at (−38, −30): 3-step podium (covered by 3
  big circle colliders), marble colonnade, back wall, pediment, large marble
  cult statue (Rig marble).
- **Eastern forum ruins** at (34, −22): broken wall rectangles (colliders).
- **Trees**: ~46 cypresses (Bark007 trunk cylinder + 2–3 stacked dark-green
  cones with high samples, `color(dark green, 0, .95)`) and dead snags,
  scattered radius 45–105, not within 32 of the arena, not in the lake. Collider 0.5.
- **Rocks**: `Sphere` geometries with vertices randomly displaced ±20% (clone
  the mesh, perturb position buffer, recompute normals via
  `mesh.updateBound()` + TangentBinormalGenerator), `Rock030` material.
- **fogWall**: translucent warm quad (Unshaded, alpha .25, BlendMode.Alpha,
  `setQueueBucket(Bucket.Transparent)`) covering the gate, `setCullHint(Always)`
  when hidden; `update` pulses its alpha when visible.
- **resolveStatics**: port 1:1 from world.js (circle colliders, ring band with
  gate sector, world boundary `C.WORLD_RADIUS`). Operate on x/z only.
- **sun**: `DirectionalLight`, direction ~`(0.45, -0.30, 0.84).normalize()`
  *pointing away from the sunset HDRI sun* — Main positions the HDRI sky so the
  glowing horizon sits at −X/−Z; color warm orange `(1.0, 0.62, 0.38)·3`.
  World creates and adds it; Main attaches shadows/scattering to it.

## Module B — `Rig.java` + `Poses.java` (port of `web/src/characters.js`)

```java
public class Rig {
    public com.jme3.scene.Node root, spinner, inner, body, headG, lArm, rArm, lLeg, rLeg, swordG, shieldG;
    public java.util.List<com.jme3.scene.Geometry> meshes;
    public float scale;

    public static class Options {  // mirrors JS buildHumanoid opts; public fields + fluent setters
        public com.jme3.math.ColorRGBA skin, tunic, crest;
        public String helmet = "none";       // "none" | "legionary" | "centurion"
        public boolean shield = false;
        public float swordLen = 0.8f, scale = 1f;
        public boolean marble = false;
    }

    public static Rig humanoid(GameCtx ctx, Options o)
}
public final class Poses {
    public static void reset(Rig r)
    public static void idle(Rig r, float time)
    public static void walk(Rig r, float cycle, float intensity)
    public static void attack(Rig r, float phase, String kind)   // "light1"|"light2"|"heavy"
    public static void roll(Rig r, float phase)
    public static void stagger(Rig r, float phase)
    public static void death(Rig r, float phase)
    public static void drink(Rig r, float phase)
    public static void rest(Rig r)
    public static void bossCombo(Rig r, float phase, int step)
    public static void bossCharge(Rig r, float phase)
    public static void bossSlam(Rig r, float phase)
    public static void shout(Rig r, float k)                     // boss roar, k = 0..1 envelope
}
```

- Same node hierarchy and pivot logic as characters.js
  (root→spinner(y=.9)→inner(y=−.9), body at y .95, head group, shoulder/hip
  pivot groups). Same pose math (the JS file is the source of truth — port the
  exact curves; `seg/smooth` helpers become private statics).
- **Realistic look**: limbs/torso from `Cylinder`/`Sphere` (≥16 radial samples)
  instead of boxes; armor = `color(steel grey, 0.85f, 0.35f)` (metallic!) with
  shoulder plates, segmented torso bands (stacked slightly-varied cylinders);
  tunic cloth `color(tunic, 0f, 0.92f)`; skin `color(skin, 0f, 0.65f)`; leather
  `color(brown, 0f, 0.8f)`. Helmet: sphere dome + brim + cheek guards; centurion
  adds a crest (curved box arc). Sword: thin box blade with `color(steel, 0.9f, 0.25f)`
  + emissive none; scutum: curved shield via partial-cylinder geometry, tunic
  color + gold boss. Marble option: every material becomes `set("Marble012")`
  tinted via `setColor("BaseColor", light grey)` — actually use one shared
  marble material for all parts.
- All meshes `ShadowMode.CastAndReceive`; collect into `meshes`.
- A `dispose()`-style fade is NOT needed; death fade is done by swapping all
  materials' BaseColor alpha — provide
  `public void setFade(float alpha)` that sets `setQueueBucket(Transparent)` +
  BaseColor alpha on every mesh (PBR BaseColor alpha + BlendMode.Alpha).

## Module C — `PlayerCtrl.java` (port of `web/src/player.js`)

```java
public class PlayerCtrl implements Combatant {
    public int vigor = 8, endurance = 8, strength = 8;
    public int gloria = 0, flasks = 4, flasksMax = 4;
    public float hp, maxHp, stamina, maxStamina;
    public String action = "free";   // free|roll|attack|drink|stagger|dead|rest
    public Combatant lockTarget;     // null when not locked
    public final com.jme3.math.Vector3f position = new com.jme3.math.Vector3f(0, 0, 21);
    public float yaw = com.jme3.math.FastMath.PI, camYaw = com.jme3.math.FastMath.PI, camPitch = 0.28f;
    public Rig rig;

    public PlayerCtrl(GameCtx ctx)            // builds rig, registers input mappings
    public void recompute()                   // maxHp = 40+vigor*13, maxStamina = 55+endurance*6
    public int lightDmg()                     // 12 + strength*2.4 (rounded)
    public java.util.List<Combatant> targets; // assigned by Main each frame before update
    public void update(float tpf)
    public void updateCamera(float tpf)       // third-person orbit + lock-on, sets ctx.cam
    public void respawn(com.jme3.math.Vector3f at)
    public void startRest() / public void standUp()
    // Combatant impl; takeDamage honours roll i-frames and rest immunity
    public boolean justDied;                  // set true once on death; GameState consumes it
}
```

- Port the whole state machine from player.js: stamina costs/regen, ATTACKS
  table (light1/light2/heavy with hit windows, combo queue, roll-cancel),
  ROLL i-frames, drink heal 60% at phase .55, sprint, lock-on selection
  (distance + camera angle score), camera (orbit, pitch clamp, lock-on lerp,
  smooth follow). Mouse deltas come from analog listeners; accumulate and
  consume per frame; sensitivity ~0.0026/0.0022 per pixel-ish unit (tune: jME
  analog values are pre-scaled, multiply by ~500 to approximate pixels).
- Movement is camera-relative WASD; entity y from `world.getHeight`. Call
  `ctx.world.resolveStatics(position, 0.45f, ctx.game.bossFight)` after moving.
- Attack hits: for each `targets` entry within range+radius and 60–80° arc
  (per-attack `arc` from JS), call `t.takeDamage(dmg, poise, position)` once
  per swing (`hits` set), spawn `ctx.fx.blood(...)` + `ctx.audio.hit()`.
- Footsteps: `ctx.audio.step()` on walk-cycle half-periods.
- While `ctx.game.phase != PLAYING` or `ctx.game.paused` or
  `ctx.game.resting`: skip input-driven movement (GameState drives those).
- Camera never goes below `getHeight(camX, camZ) + 0.4`.

## Module D — `Enemy.java`, `EnemyManager.java`, `Boss.java` (ports of enemies.js / boss.js)

```java
public class Enemy implements Combatant {
    public enum Type { LEGIONARIUS, PRAETORIANUS }  // stats exactly from enemies.js ENEMY_TYPES
    public Enemy(GameCtx ctx, Type type, com.jme3.math.Vector3f spawn, float patrolR)
    public void reset()
    public void update(float tpf)
    public void aggro()
    public String state;          // idle|chase|strafe|attack|stagger|dead|gone
    public int gloriaReward()
    public boolean justDied;      // consumed by EnemyManager
}
public class EnemyManager {
    public final java.util.List<Enemy> list = new java.util.ArrayList<>();
    public EnemyManager(GameCtx ctx)   // same 9 spawn defs as enemies.js
    public void resetAll()
    public void update(float tpf)
    public int collectGloria()         // gloria from kills since last call
    public java.util.List<Combatant> aliveList()
}
public class Boss implements Combatant {
    public static final String NAME = "CENTVRIO INVICTVS, CVSTOS AETERNVS";
    public float hp, maxHp = 950;
    public int gloria = 1800;
    public boolean defeated, justDied, enraged;
    public String state;               // dormant|shout|chase|combo|charge|slam|stagger|dead|gone
    public Boss(GameCtx ctx)
    public void reset()
    public void awaken(com.jme3.math.Vector3f playerPos)
    public boolean isActive()          // alive && !dormant
    public void update(float tpf)
}
```

- State machines, damage, cooldowns, pack-alert, separation, corpse fade
  (`rig.setFade`), respawn-on-rest: port 1:1. Death grants gloria via
  `justDied` flags exactly like the JS flow.
- Enemies use `ctx.world.resolveStatics(pos, radius, ctx.game.bossFight)` and
  terrain height for y. Targeting checks `ctx.player` alive && not resting.
- Boss: ember eyes = small `glow(red, 4)` boxes; enrage aura = PointLight
  (red, radius 14) on the rig root, intensity animated; slam shockwave uses
  `ctx.fx.shockwave(pos, 8, 0.55f)` and the timed ring damage from boss.js;
  arena tether identical.

## Module E — `Hud.java`

```java
public class Hud {
    public Hud(GameCtx ctx)
    public void update(float tpf)
    public void setVitals(float hp, float maxHp, float st, float maxSt)
    public void setFlasks(int n, int max)
    public void setGloria(int n)
    public void setPrompt(String textOrNull)
    public void message(String text, com.jme3.math.ColorRGBA color, float seconds, String sub)
    public void bossBar(String nameOrNull, float frac)
    public void hurtFlash()
    public void lowHpVignette(boolean on)
    public void lockonAt(com.jme3.math.Vector3f worldPosOrNull)  // projects via ctx.cam.getScreenCoordinates
    public void levelPanel(boolean show, int vig, int end, int str, int cost, boolean canAfford)
    public void showTitle(boolean on)     // big title + controls + "CLICCA PER INIZIARE"
    public void showPaused(boolean on)
    public void showDeathFade(float k)    // 0..1 dark red full-screen fade
}
```

- Roman aesthetic: gold `#c9a227`, bone `#d8d0c0`, blood red. Bars = layered
  Quads (dark back, colored fill scaled in x). Flasks: small amphora glyph
  approximation = "⌂"-free, just use text "VINVM ×N". All visible text in
  Italian/Latin like the JS HUD (MORTVVS ES, REQVIESCIS, GLORIA AETERNA...).
  Title screen lists the controls (WASD, mouse, Shift corsa, Spazio schivata,
  click sin/des attacchi, Q aggancio, F vinum, E interagisci, 1/2/3 al riposo).
- Everything anchored relative to `cam.getWidth()/getHeight()`; reposition on
  update is fine (no resize events needed).
- Keep references; never leak BitmapText per frame (mutate, don't recreate —
  except `message` which may rebuild its two texts).

## Module F — `SynthAudio.java`

```java
public class SynthAudio {
    public SynthAudio()                 // opens a javax.sound.sampled SourceDataLine, 44100 Hz stereo, mixer thread
    public void swing() / swingHeavy() / hit() / clang() / hurt() / roll() / step()
    public void enemyDie() / heal() / pickup() / rest() / levelup() / death() / victory() / bossRoar()
    public void startBossMusic() / stopBossMusic()
    public void startAmbient()          // looping wind (filtered noise, slow LFO swell)
    public void shutdown()
}
```

- Pure synthesis: a mixer thread sums active voices into the line. Voice =
  lambda `(t) -> sample` with duration; tones (sine/saw/square/triangle with
  exponential pitch slides + AD envelope) and filtered-noise bursts
  (one-pole/biquad band-pass approximations are fine). Port the recipes in
  `web/src/audio.js` (frequencies, durations, slides, gains).
- Boss music: 1.44 s war-drum ostinato + low saw drone chords, scheduled on the
  mixer clock. Master gain ~0.5, clamp/soft-clip the mix.
- Must be robust: if audio line unavailable, all methods become no-ops.

## Module G — `Fx.java`

```java
public class Fx {
    public Fx(GameCtx ctx)
    public void update(float tpf)
    public void blood(com.jme3.math.Vector3f pos)
    public void dust(com.jme3.math.Vector3f pos)
    public void spark(com.jme3.math.Vector3f pos)
    public void soul(com.jme3.math.Vector3f pos)        // golden rising burst
    public void shockwave(com.jme3.math.Vector3f pos, float maxR, float dur)
    public com.jme3.light.PointLight attachFire(com.jme3.scene.Node parent, boolean large)
        // flame ParticleEmitter + ember sparks + returns its flickering PointLight
    public java.util.List<com.jme3.light.PointLight> fires; // for World to flicker, base intensity stored via setName? -> store pairs internally and flicker in update()
}
```

- One-shot bursts: ParticleEmitter with `setParticlesPerSec(0)` +
  `emitAllParticles()`, detach after life expires (track in update).
- Fire: two emitters (flame: orange→dark red, rising; embers: tiny bright
  sparks) + PointLight flickered inside `Fx.update` (sin-based, like world.js).
- Shockwave: expanding flattened `Torus` with `glow(orange, 2)` material,
  fading alpha, removed at end.

---

## Reference: gameplay numbers (must match)

Player: maxHp 40+13·vig, maxSt 55+6·end, light 12+2.4·str, heavy ×2.1,
roll 0.62 s/i-frames 4–62%/stam 20, light stam 16, heavy 32, sprint 11/s,
regen 30+end after 0.7 s, flask heal 60% over 1.3 s action.
Enemies: LEGIONARIUS hp 70, speed 3.6, dmg 16, reach 2.2, aggro 15, gloria 35,
poise 50, cd 1.4, scale 1; PRAETORIANUS hp 160, speed 3.0, dmg 26, reach 2.5,
aggro 14, gloria 110, poise 110, cd 1.9, scale 1.18.
Boss: hp 950, combo 30, charge 46, slam 52, enrage <50%, gloria 1800,
poise threshold 220, stagger 1.3 s.
Level cost: `floor(70 · 1.22^(vig+end+str−24))`.
Spawns (type, x, z, patrolR): LEG(−3,−6,5) LEG(4,−22,6) LEG(−4,−38,6)
LEG(30,−20,7) LEG(38,−26,7) PRAE(34,−22,5) PRAE(−38,−22,6) LEG(−5,−52,5) LEG(6,−54,5).
