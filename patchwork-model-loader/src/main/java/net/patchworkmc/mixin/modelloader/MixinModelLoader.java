/*
 * Minecraft Forge, Patchwork Project
 * Copyright (c) 2016-2020, 2019-2020
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.patchworkmc.mixin.modelloader;

import java.util.Map;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.ModelBakeSettings;
import net.minecraft.client.render.model.ModelLoader;
import net.minecraft.client.render.model.UnbakedModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.util.Identifier;

import net.patchworkmc.impl.modelloader.ForgeModelLoader;
import net.patchworkmc.impl.modelloader.PatchworkModelBakeContext;
import net.patchworkmc.impl.modelloader.Signatures;

@Mixin(value = ModelLoader.class)
public abstract class MixinModelLoader implements ForgeModelLoader {
	@Unique
	private static final ModelIdentifier TRIDENT_INV = new ModelIdentifier("minecraft:trident_in_hand#inventory");
	@Unique
	private static final Logger LOGGER = LogManager.getLogger(ModelLoader.class);

	@Shadow
	@Final
	private Map<Identifier, UnbakedModel> modelsToBake;

	@Shadow
	@Final
	private Map<Identifier, UnbakedModel> unbakedModels;

	@Unique
	private void patchwork$loadSpecialModel() {
		for (Identifier id : getSpecialModels()) {
			ModelLoader me = (ModelLoader) (Object) this;
			UnbakedModel iunbakedmodel = me.getOrLoadModel(id);
			this.unbakedModels.put(id, iunbakedmodel);
			this.modelsToBake.put(id, iunbakedmodel);
		}
	}

	@Shadow
	private void addModel(ModelIdentifier modelId) { }

	/**
	 * Due to the limitations of mixin, when targeting a constructor, we cannot use injection points other than "TAIL".
	 * There are multiple occurrences of addModel in the constructor, Forge inserts the patch after adding model for the trident.
	 * Here we just do another check to ensure that the injection point is correct.
	 * @param me
	 * @param modelId
	 */
	@Redirect(slice = @Slice(from = @At(value = "INVOKE_STRING", target = Signatures.Profiler_swap, args = "ldc=special")),
			method = "<init>", at = @At(value = "INVOKE", target = Signatures.ModelLoader_addModel, ordinal = 0))
	private void patchwork_addModel_return(ModelLoader me, ModelIdentifier modelId) {
		addModel(modelId);

		if (modelId.equals(TRIDENT_INV)) {
			LOGGER.debug("Patchwork is loading special models for Forge mods");
			patchwork$loadSpecialModel();
		} else {
			LOGGER.warn("Patchwork was unable to load special models for Forge mods");
		}
	}

	///////////////////////////////////////////////////
	/// ModelLoader.bake and Forge's getBakedModel
	///////////////////////////////////////////////////
	@Unique
	private static final PatchworkModelBakeContext bakeContext = new PatchworkModelBakeContext();
	@Shadow
	public abstract BakedModel bake(Identifier identifier, ModelBakeSettings settings);

	@Inject(method = "bake", at = @At("HEAD"))
	public void bakeExtended_Head(CallbackInfoReturnable<BakedModel> cir) {
		bakeContext.push(getSpriteMap()::getSprite, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL);
	}

	@ModifyArg(method = "bake", at = @At(value = "INVOKE", target = Signatures.ItemModelGenerator_create))
	private Function<Identifier, Sprite> replaceTextureGetter_ItemModelGenerator_create(Function<Identifier, Sprite> dummy) {
		return bakeContext.textureGetter();
	}

	// TODO: Forge seems to forgot this patch, should we keep this?
	@ModifyArg(method = "bake", at = @At(value = "INVOKE", target = Signatures.JsonUnbakedModel_bake))
	private Function<Identifier, Sprite> replaceTextureGetter_JsonUnbakedModel_bake(Function<Identifier, Sprite> dummy) {
		return bakeContext.textureGetter();
	}

	@ModifyArg(method = "bake", at = @At(value = "INVOKE", target = Signatures.UnbakedModel_bake))
	private Function<Identifier, Sprite> replaceTextureGetter_UnbakedModel_bake(Function<Identifier, Sprite> dummy) {
		return bakeContext.textureGetter();
	}

	@Inject(method = "bake", at = @At("RETURN"))
	public void bakeExtended_Return(CallbackInfoReturnable<BakedModel> cir) {
		bakeContext.pop();
	}

	@Override
	public BakedModel getBakedModel(Identifier identifier, ModelBakeSettings settings, Function<Identifier, Sprite> textureGetter, VertexFormat vertexFormat) {
		bakeContext.setExtraParam(textureGetter, vertexFormat);
		return bake(identifier, settings);
	}

	///////////////////////////////////////////////////
	@Shadow
	@Final
	private SpriteAtlasTexture spriteAtlas;

	@Override
	public SpriteAtlasTexture getSpriteMap() {
		return spriteAtlas;
	}
}
