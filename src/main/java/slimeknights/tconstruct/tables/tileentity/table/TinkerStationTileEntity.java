package slimeknights.tconstruct.tables.tileentity.table;

import lombok.Getter;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.container.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.RecipeManager;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.hooks.BasicEventHooks;
import net.minecraftforge.items.ItemHandlerHelper;
import slimeknights.tconstruct.common.SoundUtils;
import slimeknights.tconstruct.common.Sounds;
import slimeknights.tconstruct.common.network.TinkerNetwork;
import slimeknights.tconstruct.library.recipe.RecipeTypes;
import slimeknights.tconstruct.library.recipe.tinkerstation.ITinkerStationRecipe;
import slimeknights.tconstruct.library.recipe.tinkerstation.ValidatedResult;
import slimeknights.tconstruct.shared.inventory.ConfigurableInvWrapperCapability;
import slimeknights.tconstruct.tables.TinkerTables;
import slimeknights.tconstruct.tables.inventory.table.tinkerstation.TinkerStationContainer;
import slimeknights.tconstruct.tables.network.UpdateTinkerStationRecipePacket;
import slimeknights.tconstruct.tables.tileentity.table.crafting.LazyResultInventory;
import slimeknights.tconstruct.tables.tileentity.table.crafting.TinkerStationInventoryWrapper;

import javax.annotation.Nullable;

public class TinkerStationTileEntity extends RetexturedTableTileEntity implements LazyResultInventory.ILazyCrafter {
  /** Slot index of the tool slot */
  public static final int TINKER_SLOT = 0;
  /** Slot index of the first input slot */
  public static final int INPUT_SLOT = 1;

  /** Last crafted crafting recipe */
  @Nullable
  private ITinkerStationRecipe lastRecipe;
  /** Result inventory, lazy loads results */
  @Getter
  private final LazyResultInventory craftingResult;
  /** Crafting inventory for the recipe calls */
  private final TinkerStationInventoryWrapper inventoryWrapper;

  @Getter
  private ValidatedResult currentError = ValidatedResult.PASS;

  public TinkerStationTileEntity() {
    this(6); // default to more slots
  }

  public TinkerStationTileEntity(int slots) {
    super(TinkerTables.tinkerStationTile.get(), "gui.tconstruct.tinker_station", slots);
    this.itemHandler = new ConfigurableInvWrapperCapability(this, false, false);
    this.itemHandlerCap = LazyOptional.of(() -> this.itemHandler);
    this.inventoryWrapper = new TinkerStationInventoryWrapper(this);
    this.craftingResult = new LazyResultInventory(this);
  }

  @Override
  public ITextComponent getDefaultName() {
    if (this.world == null) {
      return super.getDefaultName();
    }
    return new TranslationTextComponent(this.getBlockState().getBlock().getTranslationKey());
  }

  /**
   * Gets the number of item input slots, ignoring the tool
   * @return  Input count
   */
  public int getInputCount() {
    return getSizeInventory() - 1;
  }

  @Override
  public void resize(int size) {
    super.resize(size);
    inventoryWrapper.resize();
  }

  @Nullable
  @Override
  public Container createMenu(int menuId, PlayerInventory playerInventory, PlayerEntity playerEntity) {
    return new TinkerStationContainer(menuId, playerInventory, this);
  }

  /* Crafting */

  @Override
  public ItemStack calcResult(@Nullable PlayerEntity player) {
    if (this.world == null) {
      return ItemStack.EMPTY;
    }

    // assume empty unless we learn otherwise
    ItemStack result = ItemStack.EMPTY;
    this.currentError = ValidatedResult.PASS;

    if (!this.world.isRemote && this.world.getServer() != null) {
      RecipeManager manager = this.world.getServer().getRecipeManager();

      // first, try the cached recipe
      ITinkerStationRecipe recipe = lastRecipe;
      // if it does not match, find a new recipe
      if (recipe == null || !recipe.matches(this.inventoryWrapper, this.world)) {
        recipe = manager.getRecipe(RecipeTypes.TINKER_STATION, this.inventoryWrapper, this.world).orElse(null);
      }

      // if we have a recipe, fetch its result
      boolean needsSync = true;
      if (recipe != null) {
        // sync if the recipe is different
        if (lastRecipe != recipe) {
          this.lastRecipe = recipe;
          this.syncToRelevantPlayers(this::syncRecipe);
          needsSync = false;
        }

        // try for UI errors
        ValidatedResult validatedResult = recipe.getValidatedResult(this.inventoryWrapper);
        if (validatedResult.isSuccess()) {
          result = validatedResult.getResult();
        } else if (validatedResult.hasError()) {
          this.currentError = validatedResult;
        }
      }
      // recipe will sync screen, so only need to call it when not syncing the recipe
      if (needsSync) {
        this.syncToRelevantPlayers(this::syncScreen);
      }
    }
    // client side only needs to update result, server syncs message elsewhere
    else if (this.lastRecipe != null && this.lastRecipe.matches(this.inventoryWrapper, world)) {
      ValidatedResult validatedResult = this.lastRecipe.getValidatedResult(this.inventoryWrapper);
      if (validatedResult.isSuccess()) {
        result = validatedResult.getResult();
      } else if (validatedResult.hasError()) {
        this.currentError = validatedResult;
      }
    }

    return result;
  }

  @Override
  public ItemStack onCraft(PlayerEntity player, ItemStack result, int amount) {
    if (amount == 0 || this.lastRecipe == null || this.world == null) {
      return ItemStack.EMPTY;
    }

    // fire crafting events
    result.onCrafting(this.world, player, amount);
    BasicEventHooks.firePlayerCraftingEvent(player, result, this.inventoryWrapper);
    this.playCraftSound(player);

    // run the recipe, will shrink inputs
    // run both sides for the sake of shift clicking
    this.inventoryWrapper.setPlayer(player);
    this.lastRecipe.updateInputs(result, inventoryWrapper, !world.isRemote);
    this.inventoryWrapper.setPlayer(null);

    // remove the center slot item, just clear it entirely (if you want shrinking you should use the outer slots or ask nicely for a shrink amount hook)
    ItemStack tinkerable = this.getStackInSlot(TINKER_SLOT);
    if (!tinkerable.isEmpty()) {
      int shrinkToolSlot = lastRecipe.shrinkToolSlotBy();
      if (tinkerable.getCount() <= shrinkToolSlot) {
        this.setInventorySlotContents(TINKER_SLOT, ItemStack.EMPTY);
      } else {
        this.setInventorySlotContents(TINKER_SLOT, ItemHandlerHelper.copyStackWithSize(tinkerable, tinkerable.getCount() - shrinkToolSlot));
      }
    }

    return result;
  }

  @Override
  public void setInventorySlotContents(int slot, ItemStack itemstack) {
    super.setInventorySlotContents(slot, itemstack);
    // clear the crafting result when the matrix changes so we recalculate the result
    this.craftingResult.clear();
    this.inventoryWrapper.refreshInput(slot);
  }
  
  @Override
  protected void playCraftSound(PlayerEntity player) {
    SoundUtils.playSoundForAll(player, this.getInputCount() > 4 ? SoundEvents.BLOCK_ANVIL_USE : Sounds.SAW.getSound(), 0.8f, 0.8f + 0.4f * player.getEntityWorld().rand.nextFloat());
  }

  /* Syncing */

  /**
   * Sends the current recipe to the given player
   * @param player  Player to send an update to
   */
  public void syncRecipe(PlayerEntity player) {
    // must have a last recipe and a server world
    if (this.lastRecipe != null && this.world != null && !this.world.isRemote && player instanceof ServerPlayerEntity) {
      TinkerNetwork.getInstance().sendTo(new UpdateTinkerStationRecipePacket(this.pos, this.lastRecipe), (ServerPlayerEntity) player);
    }
  }

  /**
   * Updates the recipe from the server
   * @param recipe  New recipe
   */
  public void updateRecipe(ITinkerStationRecipe recipe) {
    this.lastRecipe = recipe;
    this.craftingResult.clear();
  }

  @Override
  public void read(BlockState blockState, CompoundNBT tags) {
    super.read(blockState, tags);
    inventoryWrapper.resize();
  }
}
