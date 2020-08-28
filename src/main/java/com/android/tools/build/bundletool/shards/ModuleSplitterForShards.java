/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.tools.build.bundletool.shards;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.android.bundle.Config.BundleConfig;
import com.android.bundle.Config.SplitDimension;
import com.android.bundle.Config.SplitDimension.Value;
import com.android.bundle.Config.SuffixStripping;
import com.android.bundle.Devices.DeviceSpec;
import com.android.tools.build.bundletool.mergers.SameTargetingMerger;
import com.android.tools.build.bundletool.model.BundleModule;
import com.android.tools.build.bundletool.model.ModuleSplit;
import com.android.tools.build.bundletool.model.OptimizationDimension;
import com.android.tools.build.bundletool.model.targeting.TargetingDimension;
import com.android.tools.build.bundletool.model.targeting.TargetingUtils;
import com.android.tools.build.bundletool.model.version.Version;
import com.android.tools.build.bundletool.splitters.AbiApexImagesSplitter;
import com.android.tools.build.bundletool.splitters.AbiNativeLibrariesSplitter;
import com.android.tools.build.bundletool.splitters.LanguageAssetsSplitter;
import com.android.tools.build.bundletool.splitters.LanguageResourcesSplitter;
import com.android.tools.build.bundletool.splitters.ModuleSplitSplitter;
import com.android.tools.build.bundletool.splitters.SanitizerNativeLibrariesSplitter;
import com.android.tools.build.bundletool.splitters.ScreenDensityResourcesSplitter;
import com.android.tools.build.bundletool.splitters.SplittingPipeline;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import javax.inject.Inject;

/** Splits bundle modules into module splits that are next merged into standalone APKs. */
public class ModuleSplitterForShards {

  private final Version bundleVersion;
  private final BundleConfig bundleConfig;
  private final Optional<DeviceSpec> deviceSpec;

  @Inject
  public ModuleSplitterForShards(
      Version bundleVersion, BundleConfig bundleConfig, Optional<DeviceSpec> deviceSpec) {
    this.bundleVersion = bundleVersion;
    this.bundleConfig = bundleConfig;
    this.deviceSpec = deviceSpec;
  }

  /** Generates a flat list of splits from a bundle module. */
  public ImmutableList<ModuleSplit> generateSplits(
      BundleModule module, ImmutableSet<OptimizationDimension> shardingDimensions) {
    ImmutableList.Builder<ModuleSplit> rawSplits = ImmutableList.builder();

    // Native libraries splits.
    SplittingPipeline nativePipeline = createNativeLibrariesSplittingPipeline(shardingDimensions);
    rawSplits.addAll(nativePipeline.split(ModuleSplit.forNativeLibraries(module)));

    // Resources splits.
    SplittingPipeline resourcesPipeline = createResourcesSplittingPipeline(shardingDimensions);
    rawSplits.addAll(resourcesPipeline.split(ModuleSplit.forResources(module)));

    // Apex images splits.
    SplittingPipeline apexPipeline = createApexImagesSplittingPipeline();
    rawSplits.addAll(apexPipeline.split(ModuleSplit.forApex(module)));

    // Assets splits.
    SplittingPipeline assetsPipeline = createAssetsSplittingPipeline(shardingDimensions);
    rawSplits.addAll(assetsPipeline.split(ModuleSplit.forAssets(module)));

    // Other files.
    rawSplits.add(ModuleSplit.forDex(module));
    rawSplits.add(ModuleSplit.forRoot(module));

    ImmutableList<ModuleSplit> unmergedSplits = rawSplits.build();

    // Strip assets for some dimensions (texture compression format).
    ImmutableList<ModuleSplit> unmergedStrippedSplits = stripAssetsWithTargeting(unmergedSplits);

    // Merge splits with the same targeting and make a single master split.
    ImmutableList<ModuleSplit> mergedSplits =
        new SameTargetingMerger().merge(unmergedStrippedSplits);

    // Remove the splitName from any standalone apks, as these are only used for instant apps (L+).
    mergedSplits =
        mergedSplits.stream().map(ModuleSplit::removeSplitName).collect(toImmutableList());

    // Check that we have only one master split.
    long masterSplitCount = mergedSplits.stream().filter(ModuleSplit::isMasterSplit).count();
    checkState(masterSplitCount == 1, "Expected one master split, got %s.", masterSplitCount);

    return mergedSplits;
  }

  /**
   * Strip assets from splits when they have a targeting that needs stripping (i.e: texture
   * compression format only for now).
   */
  private ImmutableList<ModuleSplit> stripAssetsWithTargeting(ImmutableList<ModuleSplit> splits) {
    Optional<SuffixStripping> tcfSuffixStripping =
        bundleConfig.getOptimizations().getSplitsConfig().getSplitDimensionList().stream()
            .filter(dimension -> dimension.getValue().equals(Value.TEXTURE_COMPRESSION_FORMAT))
            .findFirst()
            .map(SplitDimension::getSuffixStripping);
    if (!tcfSuffixStripping.isPresent()) {
      // In this case, return the unmodified list of splits (only TCF is supported for now in assets
      // stripping).
      return splits;
    }

    return splits.stream()
        .map(
            split ->
                stripAssetsWithTargeting(
                    split, TargetingDimension.TEXTURE_COMPRESSION_FORMAT, tcfSuffixStripping.get()))
        .collect(toImmutableList());
  }

  private static ModuleSplit stripAssetsWithTargeting(
      ModuleSplit split, TargetingDimension dimension, SuffixStripping suffixStripping) {
    // Only TCF is supported for now in assets stripping.
    checkArgument(dimension.equals(TargetingDimension.TEXTURE_COMPRESSION_FORMAT));

    // Only keep assets for the selected texture compression format.
    split =
        TargetingUtils.excludeAssetsTargetingOtherValue(
            split, dimension, suffixStripping.getDefaultSuffix());

    // Strip the targeting from the asset paths if suffix stripping is enabled.
    if (suffixStripping.getEnabled()) {
      split = TargetingUtils.removeAssetsTargeting(split, dimension);
    }

    // Apply the updated targeting to the module split (as it now only contains assets for
    // the selected TCF)
    split =
        TargetingUtils.setTargetingByDefaultSuffix(
            split, dimension, suffixStripping.getDefaultSuffix());

    return split;
  }

  private static SplittingPipeline createNativeLibrariesSplittingPipeline(
      ImmutableSet<OptimizationDimension> shardingDimensions) {
    ImmutableList.Builder<ModuleSplitSplitter> nativeSplitters = ImmutableList.builder();
    if (shardingDimensions.contains(OptimizationDimension.ABI)) {
      nativeSplitters.add(new AbiNativeLibrariesSplitter());
    }
    nativeSplitters.add(new SanitizerNativeLibrariesSplitter());

    return new SplittingPipeline(nativeSplitters.build());
  }

  private SplittingPipeline createResourcesSplittingPipeline(
      ImmutableSet<OptimizationDimension> shardingDimensions) {
    ImmutableList.Builder<ModuleSplitSplitter> resourceSplitters = ImmutableList.builder();

    if (shardingDimensions.contains(OptimizationDimension.SCREEN_DENSITY)) {
      resourceSplitters.add(
          new ScreenDensityResourcesSplitter(
              bundleVersion,
              /* pinWholeResourceToMaster= */ Predicates.alwaysFalse(),
              /* pinLowestBucketOfResourceToMaster= */ Predicates.alwaysFalse(),
              /* pinLowestBucketOfStylesToMaster= */ false));
    }

    if (shardingDimensions.contains(OptimizationDimension.LANGUAGE) && shouldSplitByLanguage()) {
      resourceSplitters.add(new LanguageResourcesSplitter());
    }

    return new SplittingPipeline(resourceSplitters.build());
  }

  private SplittingPipeline createAssetsSplittingPipeline(
      ImmutableSet<OptimizationDimension> shardingDimensions) {
    ImmutableList.Builder<ModuleSplitSplitter> assetsSplitters = ImmutableList.builder();
    if (shardingDimensions.contains(OptimizationDimension.LANGUAGE) && shouldSplitByLanguage()) {
      assetsSplitters.add(LanguageAssetsSplitter.create());
    }

    return new SplittingPipeline(assetsSplitters.build());
  }

  private boolean shouldSplitByLanguage() {
    return deviceSpec.map(spec -> !spec.getSupportedLocalesList().isEmpty()).orElse(false);
  }

  private static SplittingPipeline createApexImagesSplittingPipeline() {
    // We always split APEX image files by MultiAbi, regardless of OptimizationDimension.
    return new SplittingPipeline(ImmutableList.of(new AbiApexImagesSplitter()));
  }
}
