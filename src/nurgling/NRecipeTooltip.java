package nurgling;

import haven.*;
import haven.res.ui.tt.slot.Slotted;
import nurgling.styles.TooltipStyle;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom tooltip builder for recipe tooltips (MenuGrid pagina).
 * Renders recipe name, ingredients with quantities, skills, and description.
 */
public class NRecipeTooltip {

    // Cached foundries
    private static Text.Foundry nameFoundry = null;
    private static Text.Foundry quantityFoundry = null;
    private static Text.Foundry skillLabelFoundry = null;
    private static Text.Foundry skillNameFoundry = null;
    private static Text.Foundry descFoundry = null;

    private static Text.Foundry getNameFoundry() {
        if (nameFoundry == null) {
            nameFoundry = TooltipStyle.createFoundry(true, 12, Color.WHITE);  // Semibold
        }
        return nameFoundry;
    }

    private static Text.Foundry getQuantityFoundry() {
        if (quantityFoundry == null) {
            quantityFoundry = TooltipStyle.createFoundry(true, 11, Color.WHITE);
        }
        return quantityFoundry;
    }

    private static Text.Foundry getSkillLabelFoundry() {
        if (skillLabelFoundry == null) {
            skillLabelFoundry = TooltipStyle.createFoundry(true, 11, Color.WHITE);
        }
        return skillLabelFoundry;
    }

    private static Text.Foundry getSkillNameFoundry() {
        if (skillNameFoundry == null) {
            skillNameFoundry = TooltipStyle.createFoundry(false, 11, Color.WHITE);
        }
        return skillNameFoundry;
    }

    private static Text.Foundry getDescFoundry() {
        if (descFoundry == null) {
            descFoundry = TooltipStyle.createFoundry(false, 9, Color.WHITE);
        }
        return descFoundry;
    }

    /**
     * Build a recipe tooltip from the given name, key binding, and item info list.
     *
     * @param name Recipe name
     * @param info List of ItemInfo from the pagina
     * @return Rendered tooltip image
     */
    public static BufferedImage build(String name, List<ItemInfo> info) {
        // Render name - all white, semibold 12px
        BufferedImage ret = TooltipStyle.cropTopOnly(renderName(name));

        if (info != null && !info.isEmpty()) {

            // Extract Inputs, Skills, Cost, Slotted (gilding), Pagina, equipment stats, and food stats
            Object inputsInfo = null;
            Object skillsInfo = null;
            Object costInfo = null;
            Slotted slotted = null;
            String paginaStr = null;
            Object durabilityInfo = null;
            Object armorInfo = null;
            Object attrModInfo = null;
            Object equedInfo = null;
            ItemInfo.Tip foodInfo = null;  // NFoodInfo (has layout method)
            ItemInfo.Tip foodTypesInfo = null;  // FoodTypes

            for (ItemInfo ii : info) {
                String className = ii.getClass().getSimpleName();
                String fullName = ii.getClass().getName();

                if (className.equals("Inputs")) {
                    inputsInfo = ii;
                } else if (className.equals("Skills")) {
                    skillsInfo = ii;
                } else if (className.equals("Cost")) {
                    costInfo = ii;
                } else if (ii instanceof Slotted) {
                    slotted = (Slotted) ii;
                } else if (ii instanceof ItemInfo.Pagina) {
                    paginaStr = ((ItemInfo.Pagina) ii).str;
                } else if (className.equals("Durability")) {
                    durabilityInfo = ii;
                } else if (className.equals("Armor")) {
                    armorInfo = ii;
                } else if (className.equals("AttrMod")) {
                    attrModInfo = ii;
                } else if (className.equals("Equed")) {
                    equedInfo = ii;
                } else if (className.equals("NFoodInfo") || fullName.contains("NFoodInfo")) {
                    foodInfo = (ItemInfo.Tip) ii;
                } else if (className.equals("FoodTypes") || fullName.contains("FoodTypes")) {
                    foodTypesInfo = (ItemInfo.Tip) ii;
                }
            }

            // Render Inputs line (icons with quantities)
            BufferedImage inputsLine = null;
            if (inputsInfo != null) {
                inputsLine = TooltipStyle.cropTopOnly(renderInputsLine(inputsInfo));
            }

            // Render food stats (NFoodInfo uses custom layout)
            BufferedImage foodStatsImg = null;
            if (foodInfo != null) {
                foodStatsImg = TooltipStyle.cropTopOnly(renderFoodStats(foodInfo));
            }

            // Render food types separately (NFoodInfo.layout can't access it for recipes)
            BufferedImage foodTypesImg = null;
            if (foodTypesInfo != null) {
                foodTypesImg = TooltipStyle.cropTopOnly(renderFoodTypes(foodTypesInfo));
            }

            // Render Equed first (goes right after ingredients)
            BufferedImage equedLine = null;
            if (equedInfo != null) {
                equedLine = TooltipStyle.cropTopOnly(renderEqued(equedInfo));
            }

            // Render other equipment stats (Durability, Armor, AttrMod)
            List<BufferedImage> otherEquipmentImages = new ArrayList<>();

            if (durabilityInfo != null) {
                BufferedImage durImg = renderDurability(durabilityInfo);
                if (durImg != null) otherEquipmentImages.add(durImg);
            }

            if (armorInfo != null) {
                BufferedImage armorImg = renderArmor(armorInfo);
                if (armorImg != null) otherEquipmentImages.add(armorImg);
            }

            if (attrModInfo != null) {
                // AttrMod stats - render using NTooltip's extraction method
                List<ItemInfo> attrModList = new ArrayList<>();
                attrModList.add((ItemInfo) attrModInfo);
                NTooltip.LineResult attrModResult = NTooltip.renderGildingStatsPublic(attrModList);
                if (attrModResult != null) {
                    otherEquipmentImages.add(attrModResult.image);
                }
            }

            // Combine other equipment stats (Durability, Armor, AttrMod)
            BufferedImage otherEquipmentStats = null;
            if (!otherEquipmentImages.isEmpty()) {
                otherEquipmentStats = TooltipStyle.cropTopOnly(combineEquipmentImages(otherEquipmentImages));
            }

            // Render Gilding chance line and stats
            NTooltip.LineResult gildingChanceResult = null;
            NTooltip.LineResult gildingStatsResult = null;
            if (slotted != null) {
                gildingChanceResult = renderGildingChanceLine(slotted.pmin, slotted.pmax, slotted.attrs);
                if (slotted.sub != null && !slotted.sub.isEmpty()) {
                    gildingStatsResult = renderGildingStats(slotted.sub);
                }
            }

            // Render Skills line
            BufferedImage skillsLine = null;
            if (skillsInfo != null) {
                skillsLine = TooltipStyle.cropTopOnly(renderSkillsLine(skillsInfo));
            }

            // Render Cost line (for skills)
            BufferedImage costLine = null;
            if (costInfo != null) {
                costLine = TooltipStyle.cropTopOnly(renderCostLine(costInfo));
            }

            // Render description (Pagina) with word wrap at 200px
            BufferedImage descImg = null;
            if (paginaStr != null && !paginaStr.isEmpty()) {
                descImg = renderWrappedText(paginaStr, UI.scale(200));
            }

            // Combine with baseline-to-top spacings:
            // Name to ingredients/cost: 7px (from name baseline to next line top)
            // Between body lines: 10-12px
            // To description: 10px

            // Get font descents for baseline-relative spacing
            int nameDescent = TooltipStyle.getFontDescent(12);
            int bodyDescent = TooltipStyle.getFontDescent(11);

            // Track text offsets for proper baseline-relative spacing
            int prevTextBottomOffset = 0;
            boolean hasBodyContent = false;

            if (inputsLine != null) {
                int spacing = UI.scale(7) - nameDescent;
                ret = ItemInfo.catimgs(spacing, ret, inputsLine);
                prevTextBottomOffset = 0;  // Inputs have icons, reset offset
                hasBodyContent = true;
            }
            if (foodStatsImg != null) {
                // 10px from inputs to food stats
                int spacing = hasBodyContent ? (UI.scale(10) - bodyDescent) : (UI.scale(7) - nameDescent);
                ret = ItemInfo.catimgs(spacing, ret, foodStatsImg);
                prevTextBottomOffset = 0;  // Food stats have icons, reset offset
                hasBodyContent = true;
            }
            if (foodTypesImg != null) {
                // 10px from food stats to food types
                int spacing = hasBodyContent ? (UI.scale(10) - bodyDescent) : (UI.scale(7) - nameDescent);
                ret = ItemInfo.catimgs(spacing, ret, foodTypesImg);
                prevTextBottomOffset = 0;  // Food types have icons, reset offset
                hasBodyContent = true;
            }
            if (equedLine != null) {
                // 10px from food stats/inputs to Equed
                int spacing = hasBodyContent ? (UI.scale(10) - bodyDescent) : (UI.scale(7) - nameDescent);
                ret = ItemInfo.catimgs(spacing, ret, equedLine);
                prevTextBottomOffset = 0;  // Text line, reset offset
                hasBodyContent = true;
            }
            if (otherEquipmentStats != null) {
                // 10px from Equed (or food stats/inputs if no Equed) to other equipment stats
                int spacing = hasBodyContent ? (UI.scale(10) - bodyDescent) : (UI.scale(7) - nameDescent);
                ret = ItemInfo.catimgs(spacing, ret, otherEquipmentStats);
                prevTextBottomOffset = 0;  // Equipment stats may have icons, reset offset
                hasBodyContent = true;
            }
            if (gildingChanceResult != null) {
                // 10px from equipment stats/ingredients baseline to gilding chance text top
                int spacing = UI.scale(10) - bodyDescent - prevTextBottomOffset - gildingChanceResult.textTopOffset;
                ret = ItemInfo.catimgs(spacing, ret, gildingChanceResult.image);
                prevTextBottomOffset = gildingChanceResult.textBottomOffset;
                hasBodyContent = true;
            }
            if (gildingStatsResult != null) {
                // 10px from gilding chance baseline to first stat text top
                int spacing = UI.scale(10) - bodyDescent - prevTextBottomOffset - gildingStatsResult.textTopOffset;
                ret = ItemInfo.catimgs(spacing, ret, gildingStatsResult.image);
                prevTextBottomOffset = gildingStatsResult.textBottomOffset;
                hasBodyContent = true;
            }
            if (skillsLine != null) {
                // 10px from last stat baseline to skills text top
                int spacing = hasBodyContent ? (UI.scale(10) - bodyDescent - prevTextBottomOffset) : (UI.scale(7) - nameDescent);
                ret = ItemInfo.catimgs(spacing, ret, skillsLine);
                prevTextBottomOffset = 0;  // Skills have icons, reset offset
                hasBodyContent = true;
            }
            if (costLine != null) {
                // Cost is always 7px from name (baseline-to-top), uses 12px font
                int spacing = hasBodyContent ? (UI.scale(10) - bodyDescent) : (UI.scale(7) - nameDescent);
                ret = ItemInfo.catimgs(spacing, ret, costLine);
                hasBodyContent = true;
            }
            if (descImg != null) {
                int spacing = hasBodyContent ? (UI.scale(10) - bodyDescent) : (UI.scale(7) - nameDescent);
                ret = ItemInfo.catimgs(spacing, ret, descImg);
            }
        }

        // Add 10px padding around the content
        ret = addPadding(ret);

        return ret;
    }

    /**
     * Add 10px padding around the tooltip content.
     */
    private static BufferedImage addPadding(BufferedImage img) {
        if (img == null) return null;
        int padding = UI.scale(10);
        int newWidth = img.getWidth() + padding * 2;
        int newHeight = img.getHeight() + padding * 2;
        BufferedImage result = TexI.mkbuf(new Coord(newWidth, newHeight));
        Graphics g = result.getGraphics();
        g.drawImage(img, padding, padding, null);
        g.dispose();
        return result;
    }

    /**
     * Render recipe name - all white, semibold 12px.
     */
    private static BufferedImage renderName(String name) {
        return getNameFoundry().render(name, Color.WHITE).img;
    }

    /**
     * Render inputs line: icon + "xN" for each input.
     */
    private static BufferedImage renderInputsLine(Object inputsInfo) {
        try {
            Field inputsField = inputsInfo.getClass().getDeclaredField("inputs");
            inputsField.setAccessible(true);
            Object[] inputs = (Object[]) inputsField.get(inputsInfo);

            if (inputs == null || inputs.length == 0) return null;

            List<BufferedImage> parts = new ArrayList<>();
            int gap = UI.scale(4);

            int iconToNumGap = UI.scale(3);

            for (Object input : inputs) {
                // Get img and num fields
                Field imgField = input.getClass().getDeclaredField("img");
                Field numField = input.getClass().getDeclaredField("num");
                imgField.setAccessible(true);
                numField.setAccessible(true);

                BufferedImage icon = (BufferedImage) imgField.get(input);
                int num = numField.getInt(input);

                // Render "xN" text
                BufferedImage numImg = getQuantityFoundry().render("x" + num, Color.WHITE).img;

                // Combine icon + 3px gap + number
                int w = icon.getWidth() + iconToNumGap + numImg.getWidth();
                int h = Math.max(icon.getHeight(), numImg.getHeight());
                BufferedImage combined = TexI.mkbuf(new Coord(w, h));
                Graphics g = combined.getGraphics();
                g.drawImage(icon, 0, (h - icon.getHeight()) / 2, null);
                g.drawImage(numImg, icon.getWidth() + iconToNumGap, (h - numImg.getHeight()) / 2, null);
                g.dispose();

                parts.add(combined);
            }

            // Compose all parts horizontally with gap
            return TooltipStyle.composeHorizontalWithGap(parts, gap);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Render skills line: "Skills:" + 3px + icon + 3px + skill name.
     */
    private static BufferedImage renderSkillsLine(Object skillsInfo) {
        try {
            Field skillsField = skillsInfo.getClass().getDeclaredField("skills");
            skillsField.setAccessible(true);
            Resource[] skills = (Resource[]) skillsField.get(skillsInfo);

            if (skills == null || skills.length == 0) return null;

            int gap3 = UI.scale(3);

            // Render "Skills:" label (no trailing space - we'll add 3px gap)
            BufferedImage labelImg = getSkillLabelFoundry().render("Skills:", Color.WHITE).img;

            // Build the line manually with specific gaps
            List<BufferedImage> allParts = new ArrayList<>();
            allParts.add(labelImg);

            for (int i = 0; i < skills.length; i++) {
                Resource skill = skills[i];

                // Get skill icon and scale to 12x12
                BufferedImage scaledIcon = null;
                Resource.Image imgLayer = skill.layer(Resource.imgc);
                if (imgLayer != null) {
                    BufferedImage icon = imgLayer.img;
                    int iconSize = UI.scale(12);
                    scaledIcon = new BufferedImage(iconSize, iconSize, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g2d = scaledIcon.createGraphics();
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2d.drawImage(icon, 0, 0, iconSize, iconSize, null);
                    g2d.dispose();
                }

                // Get skill name from tooltip layer
                Resource.Tooltip tt = skill.layer(Resource.tooltip);
                String skillName = (tt != null) ? tt.t : skill.name;
                // Extract just the skill name from path if needed
                if (skillName.contains("/")) {
                    skillName = skillName.substring(skillName.lastIndexOf("/") + 1);
                    // Capitalize first letter
                    skillName = Character.toUpperCase(skillName.charAt(0)) + skillName.substring(1);
                }

                BufferedImage skillNameImg = getSkillNameFoundry().render(skillName, Color.WHITE).img;

                // Add comma before skill if not first
                if (i > 0) {
                    BufferedImage comma = getSkillNameFoundry().render(", ", Color.WHITE).img;
                    allParts.add(comma);
                }

                // Add icon and name with 3px gaps
                if (scaledIcon != null) {
                    allParts.add(scaledIcon);
                }
                allParts.add(skillNameImg);
            }

            // Compose with specific gaps: 3px after "Skills:", 3px between icon and name
            return composeSkillsLine(allParts, gap3);
        } catch (Exception e) {
            return null;
        }
    }

    // Cost color #FFFF82
    private static final Color COLOR_COST = new Color(0xFF, 0xFF, 0x82);

    /**
     * Render cost line: value + " EXP" in yellow, 12px semibold.
     */
    private static BufferedImage renderCostLine(Object costInfo) {
        try {
            Field encField = costInfo.getClass().getDeclaredField("enc");
            encField.setAccessible(true);
            int cost = encField.getInt(costInfo);

            if (cost <= 0) return null;

            // Render cost value with color #FFFF82, 12px semibold + " EXP"
            Text.Foundry costFoundry = TooltipStyle.createFoundry(true, 12, COLOR_COST);

            return costFoundry.render(Utils.thformat(cost) + " EXP", COLOR_COST).img;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Compose skills line with specific gaps:
     * - 3px after "Skills:" label
     * - 3px between icon and skill name
     * - No gap for comma (it has built-in spacing)
     */
    private static BufferedImage composeSkillsLine(List<BufferedImage> parts, int gap) {
        if (parts.isEmpty()) return null;

        // Calculate total width
        int totalWidth = 0;
        int maxHeight = 0;
        for (int i = 0; i < parts.size(); i++) {
            BufferedImage img = parts.get(i);
            totalWidth += img.getWidth();
            maxHeight = Math.max(maxHeight, img.getHeight());
            // Add gap after label (index 0) and after each icon (even indices after 0, before name)
            if (i == 0) {
                totalWidth += gap; // Gap after "Skills:"
            } else if (i > 0 && i < parts.size() - 1) {
                // Check if current is icon (12x12) and next is text
                BufferedImage next = parts.get(i + 1);
                if (img.getWidth() == UI.scale(12) && img.getHeight() == UI.scale(12) && next.getHeight() != UI.scale(12)) {
                    totalWidth += gap; // Gap between icon and name
                }
            }
        }

        BufferedImage result = TexI.mkbuf(new Coord(totalWidth, maxHeight));
        Graphics g = result.getGraphics();
        int x = 0;
        for (int i = 0; i < parts.size(); i++) {
            BufferedImage img = parts.get(i);
            g.drawImage(img, x, (maxHeight - img.getHeight()) / 2, null);
            x += img.getWidth();
            // Add gap after label and after icons
            if (i == 0) {
                x += gap; // Gap after "Skills:"
            } else if (i > 0 && i < parts.size() - 1) {
                BufferedImage next = parts.get(i + 1);
                if (img.getWidth() == UI.scale(12) && img.getHeight() == UI.scale(12) && next.getHeight() != UI.scale(12)) {
                    x += gap; // Gap between icon and name
                }
            }
        }
        g.dispose();
        return result;
    }

    /**
     * Render Durability tip with custom fonts.
     * Format: "Durability: X/Y" where X is current, Y is max.
     */
    private static BufferedImage renderDurability(Object durInfo) {
        try {
            Field aField = durInfo.getClass().getDeclaredField("a");
            Field dField = durInfo.getClass().getDeclaredField("d");
            aField.setAccessible(true);
            dField.setAccessible(true);
            int current = aField.getInt(durInfo);
            int max = dField.getInt(durInfo);

            String text = "Durability: " + current + "/" + max;
            return getQuantityFoundry().render(text, Color.WHITE).img;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Render Armor tip with custom fonts.
     * Format: "Armor: X/Y" where X is hard, Y is soft.
     */
    private static BufferedImage renderArmor(Object armorInfo) {
        try {
            Field hardField = armorInfo.getClass().getDeclaredField("hard");
            Field softField = armorInfo.getClass().getDeclaredField("soft");
            hardField.setAccessible(true);
            softField.setAccessible(true);
            int hard = hardField.getInt(armorInfo);
            int soft = softField.getInt(armorInfo);

            String text = "Armor: " + hard + "/" + soft;
            return getQuantityFoundry().render(text, Color.WHITE).img;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Render Equed tip with custom fonts.
     * Format: "Equipable in: SlotName1, SlotName2, ..."
     */
    private static BufferedImage renderEqued(Object equedInfo) {
        try {
            // Get the slots field (int[][] - 2D array of slot indices)
            Field slotsField = equedInfo.getClass().getDeclaredField("slots");
            slotsField.setAccessible(true);
            int[][] slots = (int[][]) slotsField.get(equedInfo);

            if (slots == null || slots.length == 0) {
                return null;
            }

            // Extract slot names from resources
            List<String> slotNames = new ArrayList<>();
            for (int[] slotData : slots) {
                if (slotData.length > 0) {
                    int slotIndex = slotData[0];
                    try {
                        // Load the resource for this slot (e.g., "gfx/hud/equip/ep0")
                        Resource slotRes = Resource.local().loadwait("gfx/hud/equip/ep" + slotIndex);
                        Resource.Tooltip tt = slotRes.flayer(Resource.Tooltip.class);
                        if (tt != null) {
                            slotNames.add(tt.text());
                        }
                    } catch (Exception e) {
                        // Skip slots that fail to load
                    }
                }
            }

            if (slotNames.isEmpty()) {
                return null;
            }

            String text = "Equipable in: " + String.join(", ", slotNames);
            return getQuantityFoundry().render(text, Color.WHITE).img;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Combine equipment stat images vertically with no spacing.
     */
    private static BufferedImage combineEquipmentImages(List<BufferedImage> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }

        BufferedImage result = images.get(0);
        for (int i = 1; i < images.size(); i++) {
            result = ItemInfo.catimgs(0, result, images.get(i));
        }

        return result;
    }

    /**
     * Render food stats (NFoodInfo).
     * Uses the layout() method from NFoodInfo to render with custom fonts.
     */
    private static BufferedImage renderFoodStats(ItemInfo.Tip foodInfo) {
        try {
            if (foodInfo == null) {
                return null;
            }

            // Create a layout to render the food info (needs owner from foodInfo)
            ItemInfo.Layout layout = new ItemInfo.Layout(foodInfo.owner);

            // Render NFoodInfo (energy, hunger, FEPs)
            foodInfo.layout(layout);

            // Return the rendered layout image
            return layout.render();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Render food types with icons.
     * Replicates the rendering from NFoodInfo.layout() for food types with baseline-relative spacing.
     * Manually composes images like NFoodInfo does with l.cmp.add() instead of using catimgs.
     */
    private static BufferedImage renderFoodTypes(ItemInfo.Tip foodTypesInfo) {
        try {
            // Extract types field from FoodTypes using reflection
            Field typesField = foodTypesInfo.getClass().getDeclaredField("types");
            typesField.setAccessible(true);
            Resource[] types = (Resource[]) typesField.get(foodTypesInfo);

            if (types == null || types.length == 0) {
                return null;
            }

            int lineSpacing = UI.scale(7);  // 7px between food types

            // First pass: render all food type lines and calculate total height
            List<LineResult> lines = new ArrayList<>();
            int maxWidth = 0;
            int totalHeight = 0;
            int prevTextBottomOffset = 0;
            boolean firstFoodType = true;

            for (Resource typeRes : types) {
                if (typeRes == null) continue;

                // Get food type name from resource tooltip
                String foodTypeName = null;
                Resource.Tooltip tt = typeRes.layer(Resource.Tooltip.class);
                if (tt != null) {
                    foodTypeName = tt.t;
                }
                if (foodTypeName == null) continue;

                // Get food type icon (scaled to 80% like in NFoodInfo)
                BufferedImage typeIcon = null;
                Resource.Image img = typeRes.layer(Resource.imgc);
                if (img != null) {
                    BufferedImage originalIcon = img.img;
                    int iconSize = UI.scale(TooltipStyle.ICON_SIZE);  // 13px (80% of 16px)
                    typeIcon = PUtils.convolvedown(originalIcon, new Coord(iconSize, iconSize), CharWnd.iconfilter);
                }

                // Render food type name with custom font (11px semibold, green color)
                BufferedImage nameImg = TooltipStyle.cropTopOnly(getQuantityFoundry().render(foodTypeName, TooltipStyle.COLOR_FOOD_TYPE).img);

                // Use LineElement composition like NFoodInfo does
                List<LineElement> elements = new ArrayList<>();
                if (typeIcon != null) {
                    elements.add(LineElement.icon(typeIcon));
                }
                elements.add(LineElement.text(nameImg));

                // Compose elements with proper icon/text alignment
                LineResult lineResult = composeIconTextLine(elements);
                lines.add(lineResult);

                // Calculate spacing (7px from previous baseline to current text top)
                // Baseline is descent pixels above the text bottom
                int bodyDescent = TooltipStyle.getFontDescent(TooltipStyle.FONT_SIZE_BODY);
                int spacing = firstFoodType ? 0 :
                    (lineSpacing - lineResult.textTopOffset - prevTextBottomOffset - bodyDescent);

                totalHeight += spacing + lineResult.image.getHeight();
                maxWidth = Math.max(maxWidth, lineResult.image.getWidth());

                prevTextBottomOffset = lineResult.textBottomOffset;
                firstFoodType = false;
            }

            if (lines.isEmpty()) {
                return null;
            }

            // Second pass: compose all lines into a single image at calculated positions
            BufferedImage result = TexI.mkbuf(new Coord(maxWidth, totalHeight));
            Graphics g = result.getGraphics();

            int bodyDescent = TooltipStyle.getFontDescent(TooltipStyle.FONT_SIZE_BODY);
            int y = 0;
            prevTextBottomOffset = 0;
            firstFoodType = true;
            for (LineResult line : lines) {
                int spacing = firstFoodType ? 0 :
                    (lineSpacing - line.textTopOffset - prevTextBottomOffset - bodyDescent);
                y += spacing;
                g.drawImage(line.image, 0, y, null);
                y += line.image.getHeight();
                prevTextBottomOffset = line.textBottomOffset;
                firstFoodType = false;
            }

            g.dispose();
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Helper class for line composition with text offset tracking (similar to NFoodInfo).
     */
    private static class LineResult {
        final BufferedImage image;
        final int textTopOffset;
        final int textBottomOffset;

        LineResult(BufferedImage image, int textTopOffset, int textBottomOffset) {
            this.image = image;
            this.textTopOffset = textTopOffset;
            this.textBottomOffset = textBottomOffset;
        }
    }

    /**
     * Helper class for icon/text elements (similar to NFoodInfo).
     */
    private static class LineElement {
        final BufferedImage image;
        final boolean isIcon;

        LineElement(BufferedImage image, boolean isIcon) {
            this.image = image;
            this.isIcon = isIcon;
        }

        static LineElement icon(BufferedImage img) {
            return new LineElement(img, true);
        }

        static LineElement text(BufferedImage img) {
            return new LineElement(img, false);
        }
    }

    /**
     * Compose icon and text elements with proper alignment.
     */
    private static LineResult composeIconTextLine(List<LineElement> elements) {
        if (elements.isEmpty()) {
            return new LineResult(TexI.mkbuf(new Coord(1, 1)), 0, 0);
        }

        int gap = UI.scale(2);
        int descent = TooltipStyle.getFontDescent(TooltipStyle.FONT_SIZE_BODY);

        // Find max text and icon heights
        int maxTextHeight = 0;
        int maxIconHeight = 0;
        for (LineElement elem : elements) {
            if (elem.isIcon) {
                maxIconHeight = Math.max(maxIconHeight, elem.image.getHeight());
            } else {
                maxTextHeight = Math.max(maxTextHeight, elem.image.getHeight());
            }
        }

        if (maxTextHeight == 0) maxTextHeight = maxIconHeight;

        // Total height must accommodate BOTH icon and text+descent shift
        // Text shifted down by descent/2, so we need textHeight + descent for text bounds
        int totalHeight = Math.max(maxIconHeight, maxTextHeight + descent);

        // Calculate text position (descent shifts text down for visual alignment)
        int textTopOffset = (totalHeight - maxTextHeight) / 2 + descent / 2;
        int textBottomOffset = totalHeight - textTopOffset - maxTextHeight;

        // Calculate total width
        int totalWidth = 0;
        for (int i = 0; i < elements.size(); i++) {
            totalWidth += elements.get(i).image.getWidth();
            if (i > 0) totalWidth += gap;
        }

        // Compose image
        BufferedImage result = TexI.mkbuf(new Coord(totalWidth, totalHeight));
        Graphics g = result.getGraphics();

        int x = 0;
        for (int i = 0; i < elements.size(); i++) {
            LineElement elem = elements.get(i);
            int y = elem.isIcon ?
                (totalHeight - elem.image.getHeight()) / 2 :
                (totalHeight - elem.image.getHeight()) / 2 + descent / 2;

            g.drawImage(elem.image, x, y, null);
            x += elem.image.getWidth();
            if (i < elements.size() - 1) x += gap;
        }

        g.dispose();
        return new LineResult(result, textTopOffset, textBottomOffset);
    }

    /**
     * Render text with word wrapping at specified max width.
     * Uses Open Sans 9px regular font with baseline-to-top spacing between lines.
     * Handles multiple paragraphs with 10px spacing between them.
     */
    private static BufferedImage renderWrappedText(String text, int maxWidth) {
        if (text == null || text.isEmpty()) return null;

        Text.Foundry fnd = getDescFoundry();

        // Create temporary image to get font metrics
        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = tmp.createGraphics();
        Font font = fnd.font;
        FontMetrics fm = g2d.getFontMetrics(font);
        g2d.dispose();

        // Split text into paragraphs (separated by blank lines - 2+ newlines with possible whitespace)
        // This handles \n\n, \r\n\r\n, and variations with spaces
        String[] paragraphs = text.split("\\n\\s*\\n");
        List<BufferedImage> paragraphImages = new ArrayList<>();

        for (String paragraph : paragraphs) {
            if (paragraph.trim().isEmpty()) continue;

            // Split paragraph into words and wrap
            String[] words = paragraph.trim().split("\\s+");
            List<String> lines = new ArrayList<>();
            StringBuilder currentLine = new StringBuilder();

            for (String word : words) {
                if (currentLine.length() == 0) {
                    currentLine.append(word);
                } else {
                    String testLine = currentLine + " " + word;
                    int testWidth = fm.stringWidth(testLine);
                    if (testWidth <= maxWidth) {
                        currentLine.append(" ").append(word);
                    } else {
                        // Line is full, start new line
                        lines.add(currentLine.toString());
                        currentLine = new StringBuilder(word);
                    }
                }
            }
            // Add last line
            if (currentLine.length() > 0) {
                lines.add(currentLine.toString());
            }

            if (lines.isEmpty()) continue;

            // Render each line and crop top
            List<BufferedImage> lineImages = new ArrayList<>();
            for (String line : lines) {
                BufferedImage lineImg = fnd.render(line, Color.WHITE).img;
                lineImages.add(TooltipStyle.cropTopOnly(lineImg));
            }

            // Use baseline-to-top spacing within paragraph
            int descent = TooltipStyle.getFontDescent(9);
            int lineSpacing = UI.scale(2) - descent;
            if (lineSpacing < 0) lineSpacing = 0;

            BufferedImage paragraphImg = ItemInfo.catimgs(lineSpacing, lineImages.toArray(new BufferedImage[0]));
            paragraphImages.add(paragraphImg);
        }

        if (paragraphImages.isEmpty()) return null;
        if (paragraphImages.size() == 1) return paragraphImages.get(0);

        // Combine paragraphs with 10px spacing between them
        int paragraphSpacing = UI.scale(10);
        return ItemInfo.catimgs(paragraphSpacing, paragraphImages.toArray(new BufferedImage[0]));
    }

    /**
     * Render gilding chance line: "Gilding chance: X% to Y%" with attribute icons.
     * Reuses rendering logic from NTooltip.
     */
    private static NTooltip.LineResult renderGildingChanceLine(double pmin, double pmax, Resource[] attrs) {
        // Delegate to NTooltip's rendering method
        return NTooltip.renderGildingChanceLinePublic(pmin, pmax, attrs);
    }

    /**
     * Render gilding stats from the sub info list.
     * Extracts stats using NTooltip's extraction method and renders them.
     */
    private static NTooltip.LineResult renderGildingStats(List<ItemInfo> subInfo) {
        // Delegate to NTooltip's rendering method
        return NTooltip.renderGildingStatsPublic(subInfo);
    }
}
