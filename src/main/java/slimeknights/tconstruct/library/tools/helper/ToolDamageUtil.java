package slimeknights.tconstruct.library.tools.helper;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import slimeknights.tconstruct.library.modifiers.ModifierEntry;
import slimeknights.tconstruct.library.tools.nbt.IModifierToolStack;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;
import slimeknights.tconstruct.library.tools.stat.ToolStats;

import javax.annotation.Nullable;

/**
 * Handles tool damage and repair, along with a quick broken check
 */
public class ToolDamageUtil {
  /**
   * Raw method to set a tool as broken. Bypasses {@link ToolStack} for the sake of things that may not be a full Tinker Tool
   * @param stack  Tool stack
   */
  public static void breakTool(ItemStack stack) {
    stack.getOrCreateTag().putBoolean(ToolStack.TAG_BROKEN, true);
  }

  /**
   * Checks if the given stack is broken
   * @param stack  Stack to check
   * @return  True if broken
   */
  public static boolean isBroken(ItemStack stack) {
    CompoundNBT nbt = stack.getTag();
    return nbt != null && nbt.getBoolean(ToolStack.TAG_BROKEN);
  }

  /**
   * Gets the current tool durability
   *
   * @param stack the tool stack to use
   * @return the currently durability of the tool stack
   */
  @Deprecated
  public static int getCurrentDurability(ItemStack stack) {
    if (isBroken(stack)) {
      return 0;
    }
    return stack.getMaxDamage() - stack.getDamage();
  }

  /**
   * Gets the current damage the tool has taken. Essentially the reverse of {@link #getCurrentDurability(ItemStack)}
   *
   * @param stack the tool stack to use
   * @return the currently durability of the tool stack
   */
  @Deprecated
  public static int getCurrentDamage(ItemStack stack) {
    if (isBroken(stack)) {
      return stack.getMaxDamage();
    }
    return stack.getDamage();
  }

  /**
   * Checks if the given stack needs to be repaired
   * @param stack  Stack to check
   * @return  True if it needs repair
   */
  public static boolean needsRepair(ItemStack stack) {
    return stack.getDamage() > 0 || isBroken(stack);
  }


  /* Damaging and repairing */

  /**
   * Directly damages the tool, bypassing modifier hooks
   * @param tool    Tool to damage
   * @param amount  Amount to damage
   * @param entity  Entity holding the tool
   * @param stack   Stack being damaged
   * @return  True if the tool is broken now
   */
  public static boolean directDamage(IModifierToolStack tool, int amount, @Nullable LivingEntity entity, @Nullable ItemStack stack) {
    if (entity instanceof PlayerEntity && ((PlayerEntity)entity).isCreative()) {
      return false;
    }

    int durability = tool.getStats().getInt(ToolStats.DURABILITY);
    int damage = tool.getDamage();
    int current = durability - damage;
    amount = Math.min(amount, current);
    if (amount > 0) {
      // criteria updates
      int newDamage = damage + amount;
      // TODO: needed?
      if (entity instanceof ServerPlayerEntity) {
        if (stack == null) {
          stack = entity.getHeldItemMainhand();
        }
        CriteriaTriggers.ITEM_DURABILITY_CHANGED.trigger((ServerPlayerEntity)entity, stack, newDamage);
      }

      tool.setDamage(newDamage);
      return newDamage >= durability;
    }
    return false;
  }

  /**
   * Damages the tool by the given amount
   * @param amount  Amount to damage
   * @param entity  Entity for criteria updates, if null no updates run
   * @param stack   Stack to use for criteria updates, if null uses main hand stack
   * @return true if the tool broke when damaging
   */
  public static boolean damage(IModifierToolStack tool, int amount, @Nullable LivingEntity entity, @Nullable ItemStack stack) {
    if (amount <= 0 || tool.isBroken() || tool.isUnbreakable()) {
      return false;
    }

    // try each modifier
    for (ModifierEntry entry : tool.getModifierList()) {
      amount = entry.getModifier().onDamageTool(tool, entry.getLevel(), amount, entity);
      // if no more damage, done
      if (amount < 0) {
        return false;
      }
    }
    return directDamage(tool, amount, entity, stack);
  }

  /**
   * Damages the tool and sends the break animation if it broke
   * @param tool    Tool to damage
   * @param amount  Amount of damage
   * @param entity  Entity for animation
   * @param slot    Slot containing the stack
   */
  public static boolean damageAnimated(IModifierToolStack tool, int amount, LivingEntity entity, EquipmentSlotType slot) {
    if (damage(tool, amount, entity, entity.getItemStackFromSlot(slot))) {
      entity.sendBreakAnimation(slot);
      return true;
    }
    return false;
  }

  /**
   * Damages the tool and sends the break animation if it broke
   * @param tool    Tool to damage
   * @param amount  Amount of damage
   * @param entity  Entity for animation
   * @param hand    Hand containing the stack
   * @return true if the tool broke when damaging
   */
  public static boolean damageAnimated(IModifierToolStack tool, int amount, LivingEntity entity, Hand hand) {
    if (damage(tool, amount, entity, entity.getHeldItem(hand))) {
      entity.sendBreakAnimation(hand);
      return true;
    }
    return false;
  }

  /**
   * Damages the tool in the main hand and sends the break animation if it broke
   * @param tool    Tool to damage
   * @param amount  Amount of damage
   * @param entity  Entity for animation
   */
  public static boolean damageAnimated(IModifierToolStack tool, int amount, LivingEntity entity) {
    return damageAnimated(tool, amount, entity, Hand.MAIN_HAND);
  }

  /**
   * Repairs the given tool stack
   * @param amount  Amount to repair
   */
  public static void repair(IModifierToolStack tool, int amount) {
    if (amount <= 0) {
      return;
    }

    // if undamaged, nothing to do
    int damage = tool.getDamage();
    if (damage == 0) {
      return;
    }

    // note modifiers are run in the recipe instead

    // ensure we never repair more than max durability
    int newDamage = damage - Math.min(amount, damage);
    tool.setDamage(newDamage);
  }


  /* Durability display */


  public static boolean showDurabilityBar(ItemStack stack) {
    if (!stack.getItem().isDamageable()) {
      return false;
    }
    ToolStack tool = ToolStack.from(stack);
    // if any modifier wishes to show when undamaged, let them
    for (ModifierEntry entry : tool.getModifierList()) {
      Boolean show = entry.getModifier().showDurabilityBar(tool, entry.getLevel());
      if (show != null) {
        return show;
      }
    }
    return tool.getDamage() > 0;
  }

  /**
   * Helper to avoid unneeded tool stack parsing
   * @param tool  Tool stack
   * @return  Durability for display
   */
  private static double getDamagePercentage(ToolStack tool) {
    // first modifier who wishs to handle it wins
    for (ModifierEntry entry : tool.getModifierList()) {
      double display = entry.getModifier().getDamagePercentage(tool, entry.getLevel());
      if (!Double.isNaN(display)) {
        return display;
      }
    }

    // no one took it? just use regular durability
    return (double) tool.getDamage() / tool.getStats().getInt(ToolStats.DURABILITY);
  }

  /**
   * Gets the durability to display on the stack durability bar
   * @param stack  Stack instance
   * @return  Damage taken between 0 and 1
   */
  public static double getDamageForDisplay(ItemStack stack) {
    ToolStack tool = ToolStack.from(stack);
    if (tool.isBroken()) {
      return 1;
    }
    // always show at least 5% when not broken
    return 0.95 * getDamagePercentage(tool);
  }

  /**
   * Gets the RGB to display durability at
   * @param stack  Stack instance
   * @return  RGB value
   */
  public static int getRGBDurabilityForDisplay(ItemStack stack) {
    ToolStack tool = ToolStack.from(stack);

    // first modifier who wishs to handle it wins
    for (ModifierEntry entry : tool.getModifierList()) {
      int rgb = entry.getModifier().getDurabilityRGB(tool, entry.getLevel());
      // not a problem to check against -1, the top 16 bits are unused
      if (rgb != -1) {
        return rgb;
      }
    }
    return MathHelper.hsvToRGB(Math.max(0.0f, (float) (1.0f - getDamagePercentage(tool))) / 3.0f, 1.0f, 1.0f);
  }
}
