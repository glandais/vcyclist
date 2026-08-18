<script setup lang="ts">
import type { CorridorMode, DemoEnhanceOptions } from '~/types';
import SliderInput from './SliderInput.vue';

const props = defineProps<{
    modelValue: DemoEnhanceOptions;
}>();

const emit = defineEmits<{
    'update:modelValue': [value: DemoEnhanceOptions];
}>();

const updateField = <K extends keyof DemoEnhanceOptions>(
    field: K,
    value: DemoEnhanceOptions[K]
) => {
    emit('update:modelValue', { ...props.modelValue, [field]: value });
};

const updateSimplifyField = <K extends keyof DemoEnhanceOptions['simplifyPath']>(
    field: K,
    value: DemoEnhanceOptions['simplifyPath'][K]
) => {
    emit('update:modelValue', {
        ...props.modelValue,
        simplifyPath: {
            ...props.modelValue.simplifyPath,
            [field]: value,
        },
    });
};

const updateRacingLineField = <K extends keyof DemoEnhanceOptions['racingLine']>(
    field: K,
    value: DemoEnhanceOptions['racingLine'][K]
) => {
    emit('update:modelValue', {
        ...props.modelValue,
        racingLine: { ...props.modelValue.racingLine, [field]: value },
    });
};

const updateElevationGainField = <K extends keyof DemoEnhanceOptions['elevationGain']>(
    field: K,
    value: DemoEnhanceOptions['elevationGain'][K]
) => {
    emit('update:modelValue', {
        ...props.modelValue,
        elevationGain: { ...props.modelValue.elevationGain, [field]: value },
    });
};

const elevationGainItems = [
    {
        value: 'dem',
        label: 'DEM (3 m)',
        description: "Ours. Sized for a digital elevation model's error, not a device's.",
    },
    {
        value: 'barometric',
        label: 'Barometric (2 m)',
        description: "Strava's threshold for a device with a barometric altimeter.",
    },
    {
        value: 'gps',
        label: 'GPS (10 m)',
        description: "Strava's threshold for a GPS-only trace. Reports 0 on gentle terrain.",
    },
    {
        value: 'raw',
        label: 'Raw sum',
        description:
            'No dead band. Over-reports: every wiggle counts, so finer sampling climbs more.',
    },
];

const corridorItems = [
    {
        value: 'lane',
        label: 'Own lane (right)',
        description: 'Right-hand traffic. Never crosses the centreline.',
    },
    {
        value: 'lane-left',
        label: 'Own lane (left)',
        description: 'Left-hand traffic — UK, AU, JP, IE.',
    },
    {
        value: 'full-road',
        label: 'Full road',
        description:
            'The whole carriageway. Closed roads and time trials only — illegal on an open road.',
    },
];

const updateWPrimeField = <K extends keyof DemoEnhanceOptions['wPrimeBalance']>(
    field: K,
    value: DemoEnhanceOptions['wPrimeBalance'][K]
) => {
    emit('update:modelValue', {
        ...props.modelValue,
        wPrimeBalance: {
            ...props.modelValue.wPrimeBalance,
            [field]: value,
        },
    });
};
</script>

<template>
    <div class="p-4">
        <h3 class="text-xl font-semibold text-gray-800 mb-3">🔧 Enhancement Pipeline Options</h3>

        <p class="text-gray-600 text-sm mb-6">
            Configure which processing steps are applied to the GPX track during enhancement.
        </p>

        <div class="mb-8">
            <h4 class="text-lg font-semibold text-gray-700 mb-4">Processing Steps</h4>

            <div class="flex flex-col gap-3">
                <label
                    class="flex items-start gap-3 p-4 bg-gray-50 border-2 border-gray-200 rounded-lg cursor-pointer hover:bg-gray-100 hover:border-blue-500 transition-all"
                >
                    <UCheckbox
                        :modelValue="modelValue.fixElevation"
                        @update:modelValue="updateField('fixElevation', !modelValue.fixElevation)"
                        class="mt-1"
                    />
                    <span class="flex flex-col gap-1">
                        <strong class="text-gray-800">Fix Elevation Data</strong>
                        <small class="text-gray-600 text-sm"
                            >Correct elevation using external elevation service</small
                        >
                    </span>
                </label>

                <label
                    class="flex items-start gap-3 p-4 bg-gray-50 border-2 border-gray-200 rounded-lg cursor-pointer hover:bg-gray-100 hover:border-blue-500 transition-all"
                >
                    <UCheckbox
                        :modelValue="modelValue.computeMaxSpeeds"
                        @update:modelValue="
                            updateField('computeMaxSpeeds', !modelValue.computeMaxSpeeds)
                        "
                        class="mt-1"
                    />
                    <span class="flex flex-col gap-1">
                        <strong class="text-gray-800">Compute Maximum Safe Speeds</strong>
                        <small class="text-gray-600 text-sm"
                            >Calculate cornering and braking limits (auto-enabled if virtualization
                            is on)</small
                        >
                    </span>
                </label>

                <label
                    class="flex items-start gap-3 p-4 bg-gray-50 border-2 border-gray-200 rounded-lg cursor-pointer hover:bg-gray-100 hover:border-blue-500 transition-all"
                >
                    <UCheckbox
                        :modelValue="modelValue.virtualizeTrack"
                        @update:modelValue="
                            updateField('virtualizeTrack', !modelValue.virtualizeTrack)
                        "
                        class="mt-1"
                    />
                    <span class="flex flex-col gap-1">
                        <strong class="text-gray-800">Virtualize Track</strong>
                        <small class="text-gray-600 text-sm"
                            >Simulate realistic cycling speeds using physics-based
                            calculations</small
                        >
                    </span>
                </label>

                <label
                    class="flex items-start gap-3 p-4 bg-gray-50 border-2 border-gray-200 rounded-lg cursor-pointer hover:bg-gray-100 hover:border-blue-500 transition-all"
                >
                    <UCheckbox
                        :modelValue="modelValue.computeOnePointPerSecond"
                        @update:modelValue="
                            updateField(
                                'computeOnePointPerSecond',
                                !modelValue.computeOnePointPerSecond
                            )
                        "
                        class="mt-1"
                    />
                    <span class="flex flex-col gap-1">
                        <strong class="text-gray-800">Resample to 1Hz</strong>
                        <small class="text-gray-600 text-sm"
                            >Standardize track to one point per second for consistent
                            analysis</small
                        >
                    </span>
                </label>
            </div>
        </div>

        <div class="mb-8 pt-6 border-t-2 border-gray-200">
            <h4 class="text-lg font-semibold text-gray-700 mb-4">Trajectory</h4>

            <label
                class="flex items-start gap-3 p-4 bg-gray-50 border-2 border-gray-200 rounded-lg cursor-pointer hover:bg-gray-100 hover:border-blue-500 transition-all mb-4"
            >
                <UCheckbox
                    :modelValue="modelValue.curvature.enabled"
                    @update:modelValue="
                        emit('update:modelValue', {
                            ...modelValue,
                            curvature: { enabled: !modelValue.curvature.enabled },
                        })
                    "
                    class="mt-1"
                />
                <span class="flex flex-col gap-1">
                    <strong class="text-gray-800">Estimate curvature properly</strong>
                    <small class="text-gray-600 text-sm">
                        Fits heading against arclength in a local plane instead of differencing
                        bearings over a fixed number of points. Corrects the turn radius, so it
                        corrects cornering speed — rides come out 0.2–9&nbsp;% slower and more
                        honest. Turning it off restores the older estimate; it saves nothing.
                    </small>
                </span>
            </label>

            <label
                class="flex items-start gap-3 p-4 bg-gray-50 border-2 border-gray-200 rounded-lg cursor-pointer hover:bg-gray-100 hover:border-blue-500 transition-all"
            >
                <UCheckbox
                    :modelValue="modelValue.racingLine.enabled"
                    @update:modelValue="
                        updateRacingLineField('enabled', !modelValue.racingLine.enabled)
                    "
                    class="mt-1"
                />
                <span class="flex flex-col gap-1">
                    <strong class="text-gray-800">Ride the racing line</strong>
                    <small class="text-gray-600 text-sm">
                        Straightens corners within the road, instead of tracking the centreline. Off
                        by default because it <strong>moves every coordinate</strong> of the result
                        — the map will show the line ridden, not the file you loaded.
                    </small>
                </span>
            </label>

            <div
                v-if="modelValue.racingLine.enabled"
                class="mt-6 p-6 bg-gray-50 rounded-lg border-l-4 border-violet-500"
            >
                <label class="block font-medium text-gray-800 text-base mb-3">Corridor:</label>
                <URadioGroup
                    name="corridor"
                    variant="card"
                    :items="corridorItems"
                    :modelValue="modelValue.racingLine.corridor"
                    @update:modelValue="updateRacingLineField('corridor', $event as CorridorMode)"
                />

                <div class="mt-6">
                    <SliderInput
                        :model-value="modelValue.racingLine.roadWidthM"
                        @update:model-value="updateRacingLineField('roadWidthM', $event)"
                        label="Assumed Road Width"
                        unit="m"
                        :min="3"
                        :max="12"
                        :step="0.5"
                        tooltip="Used where the GPX carries no width of its own. The corridor is half this in lane mode."
                    />
                </div>

                <p class="text-gray-700 text-sm m-0">
                    Worth little on the clock — the gain is under a percent on most routes, and on
                    some corners the line comes out <em>tighter</em> than the centreline. What it
                    changes is the shape of the ride through a bend. The map draws the recorded
                    road, the corridor and the chosen line together so the difference is visible.
                </p>
            </div>
        </div>

        <div class="mb-8 pt-6 border-t-2 border-gray-200">
            <h4 class="text-lg font-semibold text-gray-700 mb-4">Elevation</h4>

            <SliderInput
                :model-value="modelValue.elevationSmoothWindowM"
                @update:model-value="updateField('elevationSmoothWindowM', $event)"
                label="Elevation Smoothing"
                unit="m"
                :min="10"
                :max="300"
                :step="10"
                tooltip="Triangular-kernel half-width. This decides both the reported climbing and the gradients the simulation rides: it costs a clean route ~1.5% and a noisy GPS trace ~48%."
            />

            <label class="block font-medium text-gray-800 text-base mb-3 mt-6">
                Reported climbing:
            </label>
            <URadioGroup
                name="elevationGainPreset"
                variant="card"
                :items="elevationGainItems"
                :modelValue="modelValue.elevationGain.preset"
                @update:modelValue="
                    updateElevationGainField(
                        'preset',
                        $event as DemoEnhanceOptions['elevationGain']['preset']
                    )
                "
            />

            <p class="text-gray-700 text-sm mt-4 mb-0">
                Total climbing is not a property of a route — it is a property of a route
                <em>and</em> a measurement scale, the way coastline length is. The dead band above
                sets how far the ground has to rise before it counts; the smoothing slider sets the
                scale, and it is by far the larger of the two effects.
            </p>
        </div>

        <div class="mb-8 pt-6 border-t-2 border-gray-200">
            <h4 class="text-lg font-semibold text-gray-700 mb-4">W′ Balance</h4>

            <label
                class="flex items-start gap-3 p-4 bg-gray-50 border-2 border-gray-200 rounded-lg cursor-pointer hover:bg-gray-100 hover:border-blue-500 transition-all mb-6"
            >
                <UCheckbox
                    :modelValue="modelValue.wPrimeBalance.enabled"
                    @update:modelValue="
                        updateWPrimeField('enabled', !modelValue.wPrimeBalance.enabled)
                    "
                    class="mt-1"
                />
                <span class="flex flex-col gap-1">
                    <strong class="text-gray-800">Annotate the W′ balance</strong>
                    <small class="text-gray-600 text-sm"
                        >Tracks how much of the anaerobic reserve is left at each point. Adds the
                        <code>wPrimeBalance</code> field and changes nothing else — select it in the
                        Fields sidebar to plot it.</small
                    >
                </span>
            </label>

            <div
                v-if="modelValue.wPrimeBalance.enabled"
                class="p-6 bg-gray-50 rounded-lg border-l-4 border-blue-500"
            >
                <label class="flex items-start gap-3 cursor-pointer mb-4">
                    <UCheckbox
                        :modelValue="modelValue.wPrimeBalance.linkToPowerModel"
                        @update:modelValue="
                            updateWPrimeField(
                                'linkToPowerModel',
                                !modelValue.wPrimeBalance.linkToPowerModel
                            )
                        "
                        class="mt-1"
                    />
                    <span class="flex flex-col gap-1">
                        <strong class="text-gray-800">Use the power model's CP and W′</strong>
                        <small class="text-gray-600 text-sm"
                            >On by default. Untick to report the simulated rider's effort against a
                            <em>different</em> physiology — legitimate, but then the trace no longer
                            describes the rider shown in the Power tab.</small
                        >
                    </span>
                </label>

                <template v-if="!modelValue.wPrimeBalance.linkToPowerModel">
                    <SliderInput
                        :model-value="modelValue.wPrimeBalance.criticalPower"
                        @update:model-value="updateWPrimeField('criticalPower', $event)"
                        label="Reporting CP"
                        unit="W"
                        :min="100"
                        :max="450"
                        :step="5"
                        tooltip="Critical power used to score the ride, independent of the rider being simulated."
                    />

                    <SliderInput
                        :model-value="modelValue.wPrimeBalance.wPrime / 1000"
                        @update:model-value="updateWPrimeField('wPrime', $event * 1000)"
                        label="Reporting W′"
                        unit="kJ"
                        :min="5"
                        :max="40"
                        :step="1"
                        tooltip="Anaerobic work capacity used to score the ride."
                    />
                </template>

                <p class="text-gray-700 text-sm m-0">
                    Enable <strong>Resample to 1Hz</strong> above for the most faithful trace, and
                    note that <strong>Path Simplification</strong> runs after this pass with no
                    knowledge of it — a simplified path carries a sampled W′ trace, in which a deep
                    trough between two kept points can vanish.
                </p>
            </div>
        </div>

        <div class="pt-6 border-t-2 border-gray-200">
            <h4 class="text-lg font-semibold text-gray-700 mb-4">Path Simplification</h4>

            <label
                class="flex items-start gap-3 p-4 bg-gray-50 border-2 border-gray-200 rounded-lg cursor-pointer hover:bg-gray-100 hover:border-blue-500 transition-all mb-6"
            >
                <UCheckbox
                    :modelValue="modelValue.simplifyPath.enable"
                    @update:modelValue="
                        updateSimplifyField('enable', !modelValue.simplifyPath.enable)
                    "
                    class="mt-1"
                />
                <span class="flex flex-col gap-1">
                    <strong class="text-gray-800">Enable Path Simplification</strong>
                    <small class="text-gray-600 text-sm"
                        >Reduce point count using Douglas-Peucker algorithm</small
                    >
                </span>
            </label>

            <div
                v-if="modelValue.simplifyPath.enable"
                class="p-6 bg-gray-50 rounded-lg border-l-4 border-blue-500"
            >
                <SliderInput
                    :model-value="modelValue.simplifyPath.tolerance"
                    @update:model-value="updateSimplifyField('tolerance', $event)"
                    label="Tolerance"
                    unit="m"
                    :min="1"
                    :max="50"
                    :step="1"
                    tooltip="Maximum allowed distance from simplified line (higher = more aggressive simplification)"
                />

                <SliderInput
                    :model-value="modelValue.simplifyPath.zExaggeration"
                    @update:model-value="updateSimplifyField('zExaggeration', $event)"
                    label="Elevation Exaggeration"
                    :min="1"
                    :max="10"
                    :step="0.5"
                    tooltip="Factor for elevation weighting in 3D distance calculation"
                />

                <div class="mt-6 p-4 bg-green-50 rounded-md">
                    <p class="text-gray-800 text-sm m-0">
                        Current settings will simplify the path using a tolerance of
                        <strong>{{ modelValue.simplifyPath.tolerance }}m</strong> with elevation
                        weighted <strong>{{ modelValue.simplifyPath.zExaggeration }}x</strong>.
                    </p>
                </div>
            </div>
        </div>
    </div>
</template>
