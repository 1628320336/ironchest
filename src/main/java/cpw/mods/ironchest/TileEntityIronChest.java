/*******************************************************************************
 * Copyright (c) 2012 cpw.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * <p>
 * Contributors:
 * cpw - initial API and implementation
 ******************************************************************************/
package cpw.mods.ironchest;

import java.util.Arrays;
import java.util.Comparator;

import javax.annotation.Nullable;

import cpw.mods.ironchest.network.MessageCrystalChestSync;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ItemStackHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntityLockableLoot;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint;

public class TileEntityIronChest extends TileEntityLockableLoot implements ITickable, IInventory
{
    /** Chest Contents */
    public ItemStack[] chestContents;

    /** Crystal chest top stacks */
    private ItemStack[] topStacks;

    /** The current angle of the lid (between 0 and 1) */
    public float lidAngle;

    /** The angle of the lid last tick */
    public float prevLidAngle;

    /** The number of players currently using this chest */
    public int numPlayersUsing;

    /** Server sync counter (once per 20 ticks) */
    private int ticksSinceSync;

    /** Direction chest is facing */
    private EnumFacing facing;

    /** If the inventory got touched */
    private boolean inventoryTouched;

    /** If the inventory had items */
    private boolean hadStuff;

    private String customName;

    private IronChestType chestType;

    public TileEntityIronChest()
    {
        this(IronChestType.IRON);
    }

    protected TileEntityIronChest(IronChestType type)
    {
        super();
        this.chestType = type;
        this.chestContents = new ItemStack[type.size];
        this.topStacks = new ItemStack[8];
        this.facing = EnumFacing.NORTH;
    }

    public void setContents(ItemStack[] contents)
    {
        this.chestContents = new ItemStack[this.getType().size];

        for (int i = 0; i < contents.length; i++)
        {
            if (i < this.chestContents.length)
            {
                this.chestContents[i] = contents[i];
            }
        }

        this.inventoryTouched = true;
    }

    @Override
    public int getSizeInventory()
    {
        return this.chestContents.length;
    }

    public EnumFacing getFacing()
    {
        return this.facing;
    }

    public IronChestType getType()
    {
        IronChestType type = IronChestType.IRON;

        if (this.hasWorldObj())
        {
            IBlockState state = this.world.getBlockState(this.pos);

            if (state.getBlock() == IronChest.ironChestBlock)
            {
                type = state.getValue(BlockIronChest.VARIANT_PROP);
            }
        }

        return type;
    }

    @Override
    public ItemStack getStackInSlot(int index)
    {
        this.fillWithLoot((EntityPlayer) null);

        this.inventoryTouched = true;

        return this.chestContents[index];
    }

    @Override
    public void markDirty()
    {
        super.markDirty();

        this.sortTopStacks();
    }

    protected void sortTopStacks()
    {
        if (!this.getType().isTransparent() || (this.world != null && this.world.isRemote))
        {
            return;
        }

        ItemStack[] tempCopy = new ItemStack[this.getSizeInventory()];

        boolean hasStuff = false;

        int compressedIdx = 0;

        mainLoop: for (int i = 0; i < this.getSizeInventory(); i++)
        {
            ItemStack itemStack = this.chestContents[i];

            if (itemStack != null)
            {
                for (int j = 0; j < compressedIdx; j++)
                {
                    ItemStack tempCopyStack = tempCopy[j];

                    if (ItemStack.areItemsEqual(tempCopyStack, itemStack))
                    {
                        if (itemStack.stackSize != tempCopyStack.stackSize)
                        {
                            tempCopyStack.stackSize += this.chestContents[i].stackSize;
                        }

                        continue mainLoop;
                    }
                }

                tempCopy[compressedIdx] = itemStack.copy();

                compressedIdx++;

                hasStuff = true;
            }
        }

        if (!hasStuff && this.hadStuff)
        {
            this.hadStuff = false;

            for (int i = 0; i < this.topStacks.length; i++)
            {
                this.topStacks[i] = null;
            }

            if (this.world != null)
            {
                IBlockState iblockstate = this.world.getBlockState(this.pos);

                this.world.notifyBlockUpdate(this.pos, iblockstate, iblockstate, 3);
            }

            return;
        }

        this.hadStuff = true;

        Arrays.sort(tempCopy, new Comparator<ItemStack>() {
            @Override
            public int compare(ItemStack o1, ItemStack o2)
            {
                if (o1 == null)
                {
                    return 1;
                }
                else if (o2 == null)
                {
                    return -1;
                }
                else
                {
                    return o2.stackSize - o1.stackSize;
                }
            }
        });

        int p = 0;

        for (ItemStack element : tempCopy)
        {
            if (element != null && element.stackSize > 0)
            {
                if (p == this.topStacks.length)
                {
                    break;
                }

                this.topStacks[p] = element;

                p++;
            }
        }

        for (int i = p; i < this.topStacks.length; i++)
        {
            this.topStacks[i] = null;
        }

        if (this.world != null)
        {
            IBlockState iblockstate = this.world.getBlockState(this.pos);

            this.world.notifyBlockUpdate(this.pos, iblockstate, iblockstate, 3);
        }

        sendTopStacksPacket();
    }

    @Override
    @Nullable
    public ItemStack decrStackSize(int index, int count)
    {
        this.fillWithLoot((EntityPlayer) null);

        ItemStack itemstack = ItemStackHelper.getAndSplit(this.chestContents, index, count);

        if (itemstack != null)
        {
            this.markDirty();
        }

        return itemstack;
    }

    @Override
    public void setInventorySlotContents(int index, @Nullable ItemStack stack)
    {
        this.fillWithLoot((EntityPlayer) null);

        this.chestContents[index] = stack;

        if (stack != null && stack.stackSize > this.getInventoryStackLimit())
        {
            stack.stackSize = this.getInventoryStackLimit();
        }

        this.markDirty();
    }

    @Override
    public String getName()
    {
        return this.hasCustomName() ? this.customName : this.getType().name();
    }

    @Override
    public boolean hasCustomName()
    {
        return this.customName != null && this.customName.length() > 0;
    }

    public void setCustomName(String name)
    {
        this.customName = name;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound)
    {
        super.readFromNBT(compound);

        this.chestContents = new ItemStack[this.getSizeInventory()];

        if (compound.hasKey("CustomName", Constants.NBT.TAG_STRING))
        {
            this.customName = compound.getString("CustomName");
        }

        if (!this.checkLootAndRead(compound))
        {
            NBTTagList itemList = compound.getTagList("Items", Constants.NBT.TAG_COMPOUND);

            for (int itemNumber = 0; itemNumber < itemList.tagCount(); ++itemNumber)
            {
                NBTTagCompound item = itemList.getCompoundTagAt(itemNumber);

                int slot = item.getByte("Slot") & 255;

                if (slot >= 0 && slot < this.chestContents.length)
                {
                    this.chestContents[slot] = ItemStack.loadItemStackFromNBT(item);
                }
            }
        }

        this.facing = EnumFacing.VALUES[compound.getByte("facing")];

        this.sortTopStacks();
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound)
    {
        super.writeToNBT(compound);

        if (!this.checkLootAndWrite(compound))
        {
            NBTTagList itemList = new NBTTagList();

            for (int slot = 0; slot < this.chestContents.length; ++slot)
            {
                if (this.chestContents[slot] != null)
                {
                    NBTTagCompound tag = new NBTTagCompound();

                    tag.setByte("Slot", (byte) slot);

                    this.chestContents[slot].writeToNBT(tag);

                    itemList.appendTag(tag);
                }
            }

            compound.setTag("Items", itemList);
        }

        compound.setByte("facing", (byte) this.facing.ordinal());

        if (this.hasCustomName())
        {
            compound.setString("CustomName", this.customName);
        }

        return compound;
    }

    @Override
    public int getInventoryStackLimit()
    {
        return 64;
    }

    @Override
    public boolean isUsableByPlayer(EntityPlayer player)
    {
        if (this.world == null)
        {
            return true;
        }

        if (this.world.getTileEntity(this.pos) != this)
        {
            return false;
        }

        return player.getDistanceSq(this.pos.getX() + 0.5D, this.pos.getY() + 0.5D, this.pos.getZ() + 0.5D) <= 64D;
    }

    @Override
    public void update()
    {
        // Resynchronizes clients with the server state
        //@formatter:off
        if (this.world != null && !this.world.isRemote && this.numPlayersUsing != 0 && (this.ticksSinceSync + this.pos.getX() + this.pos.getY() + this.pos.getZ()) % 200 == 0)
        //@formatter:on
        {
            this.numPlayersUsing = 0;

            float f = 5.0F;

            //@formatter:off
            for (EntityPlayer player : this.world.getEntitiesWithinAABB(EntityPlayer.class, new AxisAlignedBB(this.pos.getX() - f, this.pos.getY() - f, this.pos.getZ() - f, this.pos.getX() + 1 + f, this.pos.getY() + 1 + f, this.pos.getZ() + 1 + f)))
            //@formatter:on
            {
                if (player.openContainer instanceof ContainerIronChest)
                {
                    ++this.numPlayersUsing;
                }
            }
        }

        if (this.world != null && !this.world.isRemote && this.ticksSinceSync < 0)
        {
            this.world.addBlockEvent(this.pos, IronChest.ironChestBlock, 3, ((this.numPlayersUsing << 3) & 0xF8) | (this.facing.ordinal() & 0x7));
        }

        if (!this.world.isRemote && this.inventoryTouched)
        {
            this.inventoryTouched = false;

            this.sortTopStacks();
        }

        this.ticksSinceSync++;

        this.prevLidAngle = this.lidAngle;

        float angle = 0.1F;

        if (this.numPlayersUsing > 0 && this.lidAngle == 0.0F)
        {
            double x = this.pos.getX() + 0.5D;
            double y = this.pos.getY() + 0.5D;
            double z = this.pos.getZ() + 0.5D;

            this.world.playSound(null, x, y, z, SoundEvents.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, 0.5F, this.world.rand.nextFloat() * 0.1F + 0.9F);
        }

        if (this.numPlayersUsing == 0 && this.lidAngle > 0.0F || this.numPlayersUsing > 0 && this.lidAngle < 1.0F)
        {
            float currentAngle = this.lidAngle;

            if (this.numPlayersUsing > 0)
            {
                this.lidAngle += angle;
            }
            else
            {
                this.lidAngle -= angle;
            }

            if (this.lidAngle > 1.0F)
            {
                this.lidAngle = 1.0F;
            }

            float maxAngle = 0.5F;

            if (this.lidAngle < maxAngle && currentAngle >= maxAngle)
            {
                double x = this.pos.getX() + 0.5D;
                double y = this.pos.getY() + 0.5D;
                double z = this.pos.getZ() + 0.5D;

                this.world.playSound(null, x, y, z, SoundEvents.BLOCK_CHEST_CLOSE, SoundCategory.BLOCKS, 0.5F, this.world.rand.nextFloat() * 0.1F + 0.9F);
            }

            if (this.lidAngle < 0.0F)
            {
                this.lidAngle = 0.0F;
            }
        }
    }

    @Override
    public boolean receiveClientEvent(int id, int type)
    {
        if (id == 1)
        {
            this.numPlayersUsing = type;
        }
        else if (id == 2)
        {
            this.facing = EnumFacing.VALUES[type];
        }
        else if (id == 3)
        {
            this.facing = EnumFacing.VALUES[type & 0x7];
            this.numPlayersUsing = (type & 0xF8) >> 3;
        }

        return true;
    }

    @Override
    public void openInventory(EntityPlayer player)
    {
        if (!player.isSpectator())
        {
            if (this.world == null)
            {
                return;
            }

            if (this.numPlayersUsing < 0)
            {
                this.numPlayersUsing = 0;
            }

            ++this.numPlayersUsing;

            this.world.addBlockEvent(this.pos, IronChest.ironChestBlock, 1, this.numPlayersUsing);
            this.world.notifyNeighborsOfStateChange(this.pos, IronChest.ironChestBlock);
            this.world.notifyNeighborsOfStateChange(this.pos.down(), IronChest.ironChestBlock);
        }
    }

    @Override
    public void closeInventory(EntityPlayer player)
    {
        if (!player.isSpectator())
        {
            if (this.world == null)
            {
                return;
            }

            --this.numPlayersUsing;

            this.world.addBlockEvent(this.pos, IronChest.ironChestBlock, 1, this.numPlayersUsing);
            this.world.notifyNeighborsOfStateChange(this.pos, IronChest.ironChestBlock);
            this.world.notifyNeighborsOfStateChange(this.pos.down(), IronChest.ironChestBlock);
        }
    }

    public void setFacing(EnumFacing facing)
    {
        this.facing = facing;
    }

    public ItemStack[] getTopItemStacks()
    {
        return this.topStacks;
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket()
    {
        NBTTagCompound compound = new NBTTagCompound();

        compound.setByte("facing", (byte) this.facing.ordinal());

        return new SPacketUpdateTileEntity(this.pos, 0, compound);
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt)
    {
        if (pkt.getTileEntityType() == 0)
        {
            NBTTagCompound compound = pkt.getNbtCompound();

            this.facing = EnumFacing.VALUES[compound.getByte("facing")];
        }
    }

    public ItemStack[] buildItemStackDataList()
    {
        if (this.getType().isTransparent())
        {
            ItemStack[] sortList = new ItemStack[this.topStacks.length];

            int pos = 0;

            for (ItemStack is : this.topStacks)
            {
                if (is != null)
                {
                    sortList[pos] = is;
                }
                else
                {
                    sortList[pos] = null;
                }

                pos++;
            }

            return sortList;
        }

        return null;
    }

    @Override
    @Nullable
    public ItemStack removeStackFromSlot(int index)
    {
        this.fillWithLoot((EntityPlayer) null);

        return ItemStackHelper.getAndRemove(this.chestContents, index);
    }

    @Override
    public boolean isItemValidForSlot(int index, ItemStack stack)
    {
        return this.getType().acceptsStack(stack);
    }

    public void rotateAround()
    {
        this.setFacing(this.facing.rotateY());

        this.world.addBlockEvent(this.pos, IronChest.ironChestBlock, 2, this.facing.ordinal());
    }

    public void wasPlaced(EntityLivingBase entityliving, ItemStack stack)
    {
    }

    public void removeAdornments()
    {
    }

    @Override
    public int getField(int id)
    {
        return 0;
    }

    @Override
    public void setField(int id, int value)
    {
    }

    @Override
    public int getFieldCount()
    {
        return 0;
    }

    @Override
    public void clear()
    {
        this.fillWithLoot((EntityPlayer) null);

        for (int slot = 0; slot < this.chestContents.length; ++slot)
        {
            this.chestContents[slot] = null;
        }
    }

    @Override
    public Container createContainer(InventoryPlayer playerInventory, EntityPlayer playerIn)
    {
        this.fillWithLoot((EntityPlayer) null);

        return new ContainerIronChest(playerInventory, this, this.chestType, this.chestType.xSize, this.chestType.ySize);
    }

    @Override
    public String getGuiID()
    {
        return "IronChest:" + this.getType().name();
    }

    @Override
    public boolean canRenderBreaking()
    {
        return true;
    }

    @Override
    public NBTTagCompound getUpdateTag()
    {
        return this.writeToNBT(new NBTTagCompound());
    }

    protected void sendTopStacksPacket()
    {
        ItemStack[] stacks = this.buildItemStackDataList();
        //@formatter:off
        IronChest.packetHandler.sendToAllAround(new MessageCrystalChestSync(this, stacks), new TargetPoint(world.provider.getDimension(), getPos().getX(), getPos().getY(), getPos().getZ(), 32));
        //@formatter:on
    }

    public void receiveMessageFromServer(ItemStack[] topStacks)
    {
        this.topStacks = topStacks;
    }
}
