import { fieldDefinitions, type FieldDefinitionDto } from '@glandais/vcyclist-engine';
import type { CategoryConfig, DemoFieldDefinition } from '~/types';

/**
 * Reconstructs the nested category → fields structure that the demo UI expects from
 * the flat `fieldDefinitions()` array exposed by the Kotlin/JS engine.
 *
 * Each selectable field (those without `notSelectable`) is grouped by `categoryId`, and a
 * deterministic HSL color is assigned per field based on its position in the iteration order.
 */
function getFieldConfig(): Record<string, CategoryConfig> {
    const result: Record<string, CategoryConfig> = {};
    const defs: FieldDefinitionDto[] = fieldDefinitions().filter(d => !d.notSelectable);
    const count = defs.length;

    for (const d of defs) {
        if (!result[d.categoryId]) {
            result[d.categoryId] = {
                name: d.categoryName,
                axis: d.categoryId,
                unit: '',
                fields: {},
            };
        }
        const cat = result[d.categoryId];
        const fieldDef: DemoFieldDefinition = {
            prop: d.prop,
            unit: d.unit,
            shortDescription: d.shortDescription,
            categoryId: d.categoryId,
            categoryName: d.categoryName,
            notSelectable: d.notSelectable,
            anglesInRadians: d.anglesInRadians,
            color: 'rgba(255, 0, 0, 0.8)',
        };
        cat.fields[d.prop] = fieldDef;
        if (cat.unit !== '?') {
            if (cat.unit === '') {
                cat.unit = d.unit;
            } else if (cat.unit !== d.unit) {
                cat.unit = '?';
            }
        }
    }

    let i = 0;
    const dh = count > 0 ? 360 / count : 0;
    for (const categoryId in result) {
        for (const field of Object.values(result[categoryId].fields)) {
            const h = i++ * dh;
            field.color = `hsl(${h} 50% 50%)`;
        }
    }
    return result;
}

export const fieldConfig: Record<string, CategoryConfig> = getFieldConfig();
