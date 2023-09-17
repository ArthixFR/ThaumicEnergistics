package thaumicenergistics.part;

import appeng.api.config.RedstoneMode;
import appeng.api.config.Settings;
import appeng.api.definitions.IDefinitions;
import appeng.api.implementations.items.IMemoryCard;
import appeng.api.implementations.items.MemoryCardMessages;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.util.IConfigManager;
import appeng.fluids.helper.IConfigurableFluidInventory;
import appeng.fluids.parts.PartFluidLevelEmitter;
import appeng.fluids.util.AEFluidInventory;
import appeng.helpers.IPriorityHost;
import appeng.parts.automation.PartLevelEmitter;
import appeng.tile.inventory.AppEngInternalAEInventory;
import appeng.util.SettingsFrom;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;

import appeng.api.AEApi;
import appeng.api.config.Upgrades;
import appeng.api.implementations.IUpgradeableHost;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.parts.PartItemStack;
import appeng.api.util.AECableType;

import thaumicenergistics.api.storage.IEssentiaStorageChannel;
import thaumicenergistics.item.ItemPartBase;
import thaumicenergistics.util.EssentiaFilter;
import thaumicenergistics.util.inventory.ThEUpgradeInventory;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @author BrockWS
 * @author Alex811
 */
public abstract class PartSharedEssentiaBus extends PartBase implements IGridTickable, IUpgradeableHost {

    public EssentiaFilter config;
    public ThEUpgradeInventory upgrades;
    protected boolean lastRedstone = true;
    public List<Runnable> upgradeChangeListeners = new ArrayList<>();

    public PartSharedEssentiaBus(ItemPartBase item) {
        this(item, 9, 4);
    }

    public PartSharedEssentiaBus(ItemPartBase item, int configSlots, int upgradeSlots) {
        super(item);
        this.config = new EssentiaFilter(configSlots) {
            @Override
            protected void onContentsChanged() {
                super.onContentsChanged();
                PartSharedEssentiaBus.this.host.markForSave();
            }
        };
        this.upgrades = new ThEUpgradeInventory("upgrades", upgradeSlots, 1, this.getItemStack(PartItemStack.NETWORK)) {
            @Override
            public void markDirty() {
                super.markDirty();
                PartSharedEssentiaBus.this.host.markForSave();
                upgradeChangeListeners.forEach(Runnable::run);
            }
        };
    }

    protected int calculateAmountToSend() {
        // A jar can hold 250 essentia
        // TODO: Get feedback on these values
        switch (this.getInstalledUpgrades(Upgrades.SPEED)) {
            case 4:
                return 128;
            case 3:
                return 64;
            case 2:
                return 16;
            case 1:
                return 4;
            default:
                return 1;
        }
    }

    public boolean hasInverterCard() {
        return this.getInstalledUpgrades(Upgrades.INVERTER) > 0;
    }

    public boolean hasRedstoneCard() {
        return this.getInstalledUpgrades(Upgrades.REDSTONE) > 0;
    }

    @Override
    public boolean canConnectRedstone() {
        return this.hasRedstoneCard();
    }

    @Override
    public double getIdlePowerUsage() {
        return 1;
    }

    public TileEntity getConnectedTE() {
        TileEntity self = this.host.getTile();
        World w = self.getWorld();
        BlockPos pos = self.getPos().offset(this.side.getFacing());
        if (w.getChunkProvider().getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4) != null) {
            return w.getTileEntity(pos);
        }
        return null;
    }

    protected IEssentiaStorageChannel getChannel() {
        return AEApi.instance().storage().getStorageChannel(IEssentiaStorageChannel.class);
    }

    public EssentiaFilter getConfig() {
        return this.config;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        if (tag.hasKey("config"))
            this.config.deserializeNBT(tag.getCompoundTag("config"));
        if (tag.hasKey("upgrades"))
            this.upgrades.deserializeNBT(tag.getTagList("upgrades", 10));
        this.getConfigManager().readFromNBT(tag);
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setTag("config", this.config.serializeNBT());
        tag.setTag("upgrades", this.upgrades.serializeNBT());
        this.getConfigManager().writeToNBT(tag);
    }

    @Override
    public int getInstalledUpgrades(Upgrades upgrade) {
        return this.upgrades.getUpgrades(upgrade);
    }

    @Override
    public TileEntity getTile() {
        return this.hostTile;
    }

    @Override
    public IItemHandler getInventoryByName(String s) {
        if (s.equalsIgnoreCase("upgrades")) {
            return new InvWrapper(this.upgrades);
        }
        return null;
    }

    @Override
    public float getCableConnectionLength(AECableType aeCableType) {
        return 5;
    }

    @Nonnull
    @Override
    public TickRateModulation tickingRequest(@Nonnull IGridNode node, int ticksSinceLastCall) {
        return (this.canWork() && this.workAllowedByRedstone()) ? this.doWork() : TickRateModulation.IDLE;
    }

    protected abstract TickRateModulation doWork();

    protected RedstoneMode getRSMode(){
        if (!hasRedstoneCard())
            return RedstoneMode.IGNORE;
        return (RedstoneMode) this.getConfigManager().getSetting(Settings.REDSTONE_CONTROLLED);
    }

    protected boolean hasRedstone(){
        return this.host.hasRedstone(this.side);
    }

    protected boolean workAllowedByRedstone(){
        boolean hasRedstone = this.hasRedstone();
        RedstoneMode mode = this.getRSMode();
        return !hasRedstoneCard() ||
                (mode == RedstoneMode.IGNORE) ||
                (mode == RedstoneMode.HIGH_SIGNAL && hasRedstone) ||
                (mode == RedstoneMode.LOW_SIGNAL && !hasRedstone);
    }

    @Override
    public void onNeighborChanged(IBlockAccess iBlockAccess, BlockPos blockPos, BlockPos blockPos1) {
        super.onNeighborChanged(iBlockAccess, blockPos, blockPos1);
        if(this.lastRedstone != this.hasRedstone()){
            this.lastRedstone = !this.lastRedstone;
            if(this.lastRedstone && this.canWork() && this.getRSMode() == RedstoneMode.SIGNAL_PULSE)
                this.doWork();
        }
    }

    public void uploadSettings(final SettingsFrom from, final NBTTagCompound compound, EntityPlayer player) {
        if (compound != null) {
            final IConfigManager cm = this.getConfigManager();
            if (cm != null) {
                cm.readFromNBT(compound);
            }
        }

        if (this instanceof IPriorityHost) {
            final IPriorityHost pHost = (IPriorityHost) this;
            pHost.setPriority(compound.getInteger("priority"));
        }

        EssentiaFilter target = this.config;
        EssentiaFilter tmp = new EssentiaFilter(target.getSlots());

        tmp.deserializeNBT(compound);
        for (int x = 0; x < tmp.getSlots(); x++) {
            target.setAspect(tmp.getAspect(x), x);
        }
    }

    protected NBTTagCompound downloadSettings(final SettingsFrom from) {
        final NBTTagCompound output = new NBTTagCompound();

        final IConfigManager cm = this.getConfigManager();
        if (cm != null) {
            cm.writeToNBT(output);
        }

        if (this instanceof IPriorityHost) {
            final IPriorityHost pHost = (IPriorityHost) this;
            output.setInteger("priority", pHost.getPriority());
        }

        return this.config.serializeNBT();
    }

    public boolean useStandardMemoryCard() {
        return true;
    }

    protected boolean useMemoryCard(final EntityPlayer player) {
        final ItemStack memCardIS = player.inventory.getCurrentItem();

        if (!memCardIS.isEmpty() && this.useStandardMemoryCard() && memCardIS.getItem() instanceof IMemoryCard) {
            final IMemoryCard memoryCard = (IMemoryCard) memCardIS.getItem();

            ItemStack is = this.getItemStack(PartItemStack.NETWORK);

            // Blocks and parts share the same soul!
            final IDefinitions definitions = AEApi.instance().definitions();
            if (definitions.parts().iface().isSameAs(is)) {
                Optional<ItemStack> iface = definitions.blocks().iface().maybeStack(1);
                if (iface.isPresent()) {
                    is = iface.get();
                }
            }

            final String name = is.getItem().getUnlocalizedNameInefficiently(is);

            if (player.isSneaking()) {
                final NBTTagCompound data = this.downloadSettings(SettingsFrom.MEMORY_CARD);
                if (data != null) {
                    memoryCard.setMemoryCardContents(memCardIS, name, data);
                    memoryCard.notifyUser(player, MemoryCardMessages.SETTINGS_SAVED);
                }
            } else {
                final String storedName = memoryCard.getSettingsName(memCardIS);
                final NBTTagCompound data = memoryCard.getData(memCardIS);
                if (name.equals(storedName)) {
                    this.uploadSettings(SettingsFrom.MEMORY_CARD, data, player);
                    memoryCard.notifyUser(player, MemoryCardMessages.SETTINGS_LOADED);
                } else {
                    memoryCard.notifyUser(player, MemoryCardMessages.INVALID_MACHINE);
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean onActivate(EntityPlayer entityPlayer, EnumHand enumHand, Vec3d vec3d) {
        if (this.useMemoryCard(entityPlayer)) {
            return true;
        }

        return false;
    }

    @Override
    public boolean onShiftActivate(EntityPlayer entityPlayer, EnumHand enumHand, Vec3d vec3d) {
        if (this.useMemoryCard(entityPlayer)) {
            return true;
        }

        return false;
    }

    @Override
    public boolean onClicked(EntityPlayer player, EnumHand hand, Vec3d pos) {
        if (this.useMemoryCard(player)) {
            return true;
        }
        return super.onClicked(player, hand, pos);
    }
}
