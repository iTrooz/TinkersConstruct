package slimeknights.tconstruct.library.client.model.tools;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.datafixers.util.Pair;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.model.BakedQuad;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.client.renderer.model.IModelTransform;
import net.minecraft.client.renderer.model.IUnbakedModel;
import net.minecraft.client.renderer.model.ItemCameraTransforms.TransformType;
import net.minecraft.client.renderer.model.ItemOverrideList;
import net.minecraft.client.renderer.model.ModelBakery;
import net.minecraft.client.renderer.model.RenderMaterial;
import net.minecraft.client.renderer.texture.MissingTextureSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.inventory.container.PlayerContainer;
import net.minecraft.item.ItemStack;
import net.minecraft.resources.IResourceManager;
import net.minecraft.util.JSONUtils;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.TransformationMatrix;
import net.minecraft.util.math.vector.Vector2f;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraftforge.client.model.BakedItemModel;
import net.minecraftforge.client.model.IModelConfiguration;
import net.minecraftforge.client.model.IModelLoader;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.client.model.PerspectiveMapWrapper;
import net.minecraftforge.client.model.geometry.IModelGeometry;
import org.apache.commons.lang3.mutable.MutableObject;
import slimeknights.mantle.client.model.util.MantleItemLayerModel;
import slimeknights.mantle.util.ItemLayerPixels;
import slimeknights.tconstruct.common.config.Config;
import slimeknights.tconstruct.library.client.materials.MaterialRenderInfo;
import slimeknights.tconstruct.library.client.materials.MaterialRenderInfo.TintedSprite;
import slimeknights.tconstruct.library.client.materials.MaterialRenderInfoLoader;
import slimeknights.tconstruct.library.materials.definition.MaterialId;
import slimeknights.tconstruct.library.tools.part.IMaterialItem;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

@AllArgsConstructor
@Log4j2
public class MaterialModel implements IModelGeometry<MaterialModel> {
  /** Set of all textures that are missing from the resource pack, to avoid logging twice */
  private static final Set<ResourceLocation> SKIPPED_TEXTURES = new HashSet<>();

  /** Shared loader instance */
  public static final Loader LOADER = new Loader();

  /** If null, uses dynamic material */
  @Nullable
  private final MaterialId material;
  /** Tint index and index of part in tool */
  private final int index;
  /** Transform matrix to apply to child parts */
  private final Vector2f offset;

  @Override
  public Collection<RenderMaterial> getTextures(IModelConfiguration owner, Function<ResourceLocation,IUnbakedModel> modelGetter, Set<Pair<String,String>> missingTextureErrors) {
    Set<RenderMaterial> allTextures = Sets.newHashSet();
    getMaterialTextures(allTextures, owner, "texture", material);
    return allTextures;
  }

  /** Checks if a texture exists */
  private static boolean textureExists(IResourceManager manager, ResourceLocation location) {
    return manager.hasResource(new ResourceLocation(location.getNamespace(), "textures/" + location.getPath() + ".png"));
  }

  /**
   * Gets a consumer to add textures to the given collection
   * @param textureLocation  Unused, will be removed in 1.18
   * @param allTextures      Collection of textures
   * @return  Texture consumer
   */
  public static Predicate<RenderMaterial> getTextureAdder(ResourceLocation textureLocation, Collection<RenderMaterial> allTextures, boolean logMissingTextures) {
    IResourceManager manager = Minecraft.getInstance().getResourceManager();
    return mat -> {
      // either must be non-blocks, or must exist. We have fallbacks if it does not exist
      ResourceLocation loc = mat.getTextureLocation();
      if (!PlayerContainer.LOCATION_BLOCKS_TEXTURE.equals(mat.getAtlasLocation()) || textureExists(manager, loc)) {
        allTextures.add(mat);
        return true;
      } else if (logMissingTextures && !SKIPPED_TEXTURES.contains(loc)) {
        SKIPPED_TEXTURES.add(loc);
        log.debug("Skipping loading texture '{}' as it does not exist in the resource pack", loc);
      }
      return false;
    };
  }

  /**
   * Gets the list of material textures for the given owner texture
   * @param allTextures  Collection of textures
   * @param owner        Model owner
   * @param textureName  Texture name to add
   * @param material     List of materials
   */
  public static void getMaterialTextures(Collection<RenderMaterial> allTextures, IModelConfiguration owner, String textureName, @Nullable MaterialId material) {
    RenderMaterial texture = owner.resolveTexture(textureName);
    allTextures.add(texture);

    // if the texture is missing, stop here
    if (!MissingTextureSprite.getLocation().equals(texture.getTextureLocation())) {
      // texture should exist in item/tool, or the validator cannot handle them
      Predicate<RenderMaterial> textureAdder = getTextureAdder(texture.getTextureLocation(), allTextures, Config.CLIENT.logMissingMaterialTextures.get());
      // if no specific material is set, load all materials as dependencies. If just one material, use just that one
      if (material == null) {
        MaterialRenderInfoLoader.INSTANCE.getAllRenderInfos().forEach(info -> info.getTextureDependencies(textureAdder, texture));
      } else {
        MaterialRenderInfoLoader.INSTANCE.getRenderInfo(material).ifPresent(info -> info.getTextureDependencies(textureAdder, texture));
      }
    }
  }

  /**
   * Gets the quads for a material for the given texture
   * @param owner         Model owner
   * @param spriteGetter  Sprite getter
   * @param transform     Model transform
   * @param name          Sprite name
   * @param index         Sprite tint index
   * @param material      Material to use
   * @return  Model quads
   */
  public static TextureAtlasSprite getPartQuads(Consumer<ImmutableList<BakedQuad>> quadConsumer, IModelConfiguration owner, Function<RenderMaterial, TextureAtlasSprite> spriteGetter, TransformationMatrix transform, String name, int index, @Nullable MaterialId material) {
    return getPartQuads(quadConsumer, owner, spriteGetter, transform, name, index, material, null);
  }

  /**
   * Gets the quads for a material for the given texture
   * @param owner         Model owner
   * @param spriteGetter  Sprite getter
   * @param transform     Model transform
   * @param name          Sprite name
   * @param index         Sprite tint index
   * @param material      Material to use
   * @param pixels        Pixels for the z-fighting fix. See {@link MantleItemLayerModel} for more information
   * @return  Model quads
   */
  public static TextureAtlasSprite getPartQuads(Consumer<ImmutableList<BakedQuad>> quadConsumer, IModelConfiguration owner, Function<RenderMaterial, TextureAtlasSprite> spriteGetter, TransformationMatrix transform, String name, int index, @Nullable MaterialId material, @Nullable ItemLayerPixels pixels) {
    return getPartQuads(quadConsumer, owner.resolveTexture(name), spriteGetter, transform, index, material, pixels);
  }

  /**
   * Gets the quads for a material for the given texture
   * @param texture       Base texture
   * @param spriteGetter  Sprite getter
   * @param transform     Model transform
   * @param index         Sprite tint index
   * @param material      Material to use
   * @param pixels        Pixels for the z-fighting fix. See {@link MantleItemLayerModel} for more information
   * @return  Model quads
   */
  public static TextureAtlasSprite getPartQuads(Consumer<ImmutableList<BakedQuad>> quadConsumer, RenderMaterial texture, Function<RenderMaterial, TextureAtlasSprite> spriteGetter, TransformationMatrix transform, int index, @Nullable MaterialId material, @Nullable ItemLayerPixels pixels) {
    int color = -1;
    int light = 0;
    TextureAtlasSprite finalSprite = null;
    // if the base material is non-null, try to find the sprite for that material
    if (material != null) {
      // first, find a render info
      Optional<MaterialRenderInfo> optional = MaterialRenderInfoLoader.INSTANCE.getRenderInfo(material);
      if (optional.isPresent()) {
        // determine the texture to use and whether or not to tint it
        MaterialRenderInfo info = optional.get();
        TintedSprite sprite = info.getSprite(texture, spriteGetter);
        finalSprite = sprite.getSprite();
        color = sprite.getColor();
        light = info.getLuminosity();
      }
    }

    // if we have no material, or the material failed to fetch, use the default sprite and tint index
    if (finalSprite == null) {
      finalSprite = spriteGetter.apply(texture);
    }

    // get quads
    quadConsumer.accept(MantleItemLayerModel.getQuadsForSprite(color, index, finalSprite, transform, light, pixels));

    // return sprite
    return finalSprite;
  }

  /**
   * Same as {@link #bake(IModelConfiguration, ModelBakery, Function, IModelTransform, ItemOverrideList, ResourceLocation)}, but uses fewer arguments and does not require an instance
   * @param owner          Model configuration
   * @param spriteGetter   Sprite getter function
   * @param transform      Transform to apply to the quad fetching. Should not include rotation or it will look wrong in UIs
   * @param material       Material used, if null uses default
   * @param index          Tint index to use if tinted sprite is used
   * @param overrides      Override instance to use, will either be empty or {@link MaterialOverrideHandler}
   * @return  Baked model
   */
  private static IBakedModel bakeInternal(IModelConfiguration owner, Function<RenderMaterial, TextureAtlasSprite> spriteGetter, TransformationMatrix transform, @Nullable MaterialId material, int index, ItemOverrideList overrides) {
    // small hack to reduce the need to create a second immutable list
    MutableObject<ImmutableList<BakedQuad>> mutableList = new MutableObject<>();
    TextureAtlasSprite particle = getPartQuads(mutableList::setValue, owner, spriteGetter, transform, "texture", index, material);

    // bake model - while the transform may not be identity, it never has rotation so its safe to say untransformed
    ImmutableMap<TransformType, TransformationMatrix> transformMap = PerspectiveMapWrapper.getTransforms(owner.getCombinedTransform());
    return new BakedItemModel(mutableList.getValue(), particle, Maps.immutableEnumMap(transformMap), overrides, true, owner.isSideLit());
  }

  @Override
  public IBakedModel bake(IModelConfiguration owner, ModelBakery bakery, Function<RenderMaterial, TextureAtlasSprite> spriteGetter, IModelTransform modelTransform, ItemOverrideList vanillaOverrides, ResourceLocation modelLocation) {
    // create transforms from offset
    TransformationMatrix transforms;
    if (Vector2f.ZERO.equals(offset)) {
      transforms = TransformationMatrix.identity();
    } else {
      // divide by 16 to convert from pixels to base values
      // negate Y as positive is up for transforms but down for pixels
      transforms = new TransformationMatrix(new Vector3f(offset.x / 16, -offset.y / 16, 0), null, null, null);
    }

    // if the material is already set, no need to set overrides
    ItemOverrideList overrides = ItemOverrideList.EMPTY;
    if (material == null) {
      overrides = new MaterialOverrideHandler(owner, index, transforms);
    }

    // after that its base logic
    return bakeInternal(owner, spriteGetter, transforms, material, index, overrides);
  }

  /**
   * Dynamic override handler to swap in the material texture
   */
  private static final class MaterialOverrideHandler extends ItemOverrideList {
    // contains all the baked models since they'll never change, cleared automatically as the baked model is discarded
    private final Map<MaterialId, IBakedModel> cache = new ConcurrentHashMap<>();

    // parameters needed for rebaking
    private final IModelConfiguration owner;
    private final int index;
    private final TransformationMatrix itemTransform;
    private MaterialOverrideHandler(IModelConfiguration owner, int index, TransformationMatrix itemTransform) {
      this.owner = owner;
      this.index = index;
      this.itemTransform = itemTransform;
    }

    @Override
    public IBakedModel getOverrideModel(IBakedModel originalModel, ItemStack stack, @Nullable ClientWorld world, @Nullable LivingEntity entity) {
      // fetch the material from the stack
      MaterialId material = IMaterialItem.getMaterialIdFromStack(stack);
      // cache all baked material models, they will not need to be recreated as materials will not change
      return cache.computeIfAbsent(material, this::bakeDynamic);
    }

    /**
     * Bakes a copy of this model using the given material
     * @param material  New material for the model
     * @return  Baked model
     */
    private IBakedModel bakeDynamic(MaterialId material) {
      // bake internal does not require an instance to bake, we can pass in whatever material we want
      // use empty override list as the sub model never calls overrides, and already has a material
      return bakeInternal(owner, ModelLoader.defaultTextureGetter(), itemTransform, material, index, ItemOverrideList.EMPTY);
    }
  }

  /**
   * Model loader logic, use {@link #LOADER} to access instance
   */
  private static class Loader implements IModelLoader<MaterialModel> {
    @Override
    public void onResourceManagerReload(IResourceManager resourceManager) {
      SKIPPED_TEXTURES.clear();
    }

    @Override
    public MaterialModel read(JsonDeserializationContext deserializationContext, JsonObject modelContents) {
      // need tint index for tool models, doubles as part index
      int index = JSONUtils.getInt(modelContents, "index", 0);

      // static material can be defined, if unset uses dynamic material
      MaterialId material = null;
      if (modelContents.has("material")) {
        material = new MaterialId(JSONUtils.getString(modelContents, "material"));
      }

      Vector2f offset = Vector2f.ZERO;
      if (modelContents.has("offset")) {
        offset = arrayToObject(modelContents, "offset");
      }

      return new MaterialModel(material, index, offset);
    }
  }


  /* Helpers */

  /**
   * Converts a JSON float array to the specified object
   * @param json    JSON object
   * @param name    Name of the array in the object to fetch
   * @return  Vector3f of data
   * @throws JsonParseException  If there is no array or the length is wrong
   */
  public static Vector2f arrayToObject(JsonObject json, String name) {
    JsonArray array = JSONUtils.getJsonArray(json, name);
    if (array.size() != 2) {
      throw new JsonParseException("Expected " + 2 + " " + name + " values, found: " + array.size());
    }
    float[] vec = new float[2];
    for(int i = 0; i < 2; ++i) {
      vec[i] = JSONUtils.getFloat(array.get(i), name + "[" + i + "]");
    }
    return new Vector2f(vec[0], vec[1]);
  }
}
