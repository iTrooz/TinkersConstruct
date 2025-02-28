package slimeknights.tconstruct.tools.modifiers.traits.harvest;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.event.entity.player.PlayerEvent.BreakSpeed;
import slimeknights.tconstruct.TConstruct;
import slimeknights.tconstruct.common.TinkerTags;
import slimeknights.tconstruct.library.modifiers.Modifier;
import slimeknights.tconstruct.library.tools.nbt.IModifierToolStack;
import slimeknights.tconstruct.library.utils.TooltipFlag;
import slimeknights.tconstruct.library.utils.TooltipKey;

import javax.annotation.Nullable;
import java.util.List;

public class DwarvenModifier extends Modifier {
  private static final ITextComponent MINING_SPEED = TConstruct.makeTranslation("modifier", "dwarven.mining_speed");
  /** Baseline height where boost is 1 */
  private static final int SEA_LEVEL = 64;
  /** Max percentage bonus per level when y = 0 */
  private static final float BOOST_AT_0 = 0.1f;

  public DwarvenModifier() {
    super(0xF98648);
  }

  /** Gets the boost for the given level and height */
  private static float getBoost(int y, int level) {
    return (SEA_LEVEL - y) * level * (BOOST_AT_0 / SEA_LEVEL);
  }

  @Override
  public void onBreakSpeed(IModifierToolStack tool, int level, BreakSpeed event, Direction sideHit, boolean isEffective, float miningSpeedModifier) {
    if (!isEffective) {
      return;
    }
    // essentially just the line slope formula from (0, level + 1) to (SEA_LEVEL, 1), with a scal
    float factor = getBoost(event.getPos().getY(), level);
    if (factor > 0) {
      event.setNewSpeed(event.getNewSpeed() * (1 + factor));
    }
  }

  @Override
  public void addInformation(IModifierToolStack tool, int level, @Nullable PlayerEntity player, List<ITextComponent> tooltip, TooltipKey key, TooltipFlag tooltipFlag) {
    if (tool.hasTag(TinkerTags.Items.HARVEST)) {
      double boost;
      if (player != null && key == TooltipKey.SHIFT) {
        boost = getBoost((int)player.getPosY(), level);
      } else {
        boost = BOOST_AT_0 * level;
      }
      if (boost > 0) {
        addPercentTooltip(MINING_SPEED, boost, tooltip);
      }
    }
  }
}
