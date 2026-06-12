package aeternum;

import com.jme3.asset.AssetManager;
import com.jme3.asset.AssetNotFoundException;
import com.jme3.asset.TextureKey;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.texture.Texture;

/** Builds PBR materials from the bundled CC0 texture sets (ambientCG naming). */
public class TexLib {
    private final AssetManager assets;

    public TexLib(AssetManager assets) {
        this.assets = assets;
    }

    public Texture tex(String path) {
        Texture t = assets.loadTexture(new TextureKey(path, true));
        t.setWrap(Texture.WrapMode.Repeat);
        return t;
    }

    /**
     * Full textured PBR material from a bundled set, e.g. set("Grass004").
     * Remember: meshes using this need tangents
     * (TangentBinormalGenerator.generate(geometry)).
     */
    public Material set(String name) {
        Material m = new Material(assets, "Common/MatDefs/Light/PBRLighting.j3md");
        String base = "Textures/" + name + "/" + name + "_1K-JPG_";
        m.setTexture("BaseColorMap", tex(base + "Color.jpg"));
        m.setTexture("NormalMap", tex(base + "NormalGL.jpg"));
        m.setTexture("RoughnessMap", tex(base + "Roughness.jpg"));
        // note: no AO map — screen-space AO comes from the SSAO filter instead
        m.setFloat("Metallic", 0f);
        return m;
    }

    /** Untextured PBR material with a flat color. */
    public Material color(ColorRGBA c, float metallic, float roughness) {
        Material m = new Material(assets, "Common/MatDefs/Light/PBRLighting.j3md");
        m.setColor("BaseColor", c);
        m.setFloat("Metallic", metallic);
        m.setFloat("Roughness", roughness);
        return m;
    }

    /** Emissive (glowing) material, picked up by the bloom filter. */
    public Material glow(ColorRGBA c, float power) {
        Material m = new Material(assets, "Common/MatDefs/Light/PBRLighting.j3md");
        m.setColor("BaseColor", c);
        m.setColor("Emissive", c.mult(power));
        m.setFloat("Metallic", 0f);
        m.setFloat("Roughness", 1f);
        return m;
    }
}
