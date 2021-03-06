package com.bigeebs.quantumprocessing.block.LeadFurnace;


import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.*;
import net.minecraft.item.*;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.server.gui.IUpdatePlayerListBox;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.tileentity.TileEntityLockable;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Arrays;


/**
 * Created by Andrew.Ebert on 9/23/2015.
 */
public class LeadFurnaceTileEntity  extends TileEntity implements IInventory, IUpdatePlayerListBox {

    private final int NUMBER_OF_SLOTS = 4;
    private final int PROCESS_SLOT_1 = 0;
    private final int PROCESS_SLOT_2 = 1;
    private final int FUEL_SLOT = 2;
    private final int OUTPUT_SLOT = 3;
    private ItemStack[] itemStacks = new ItemStack[NUMBER_OF_SLOTS];

    /** The number of ticks that the furnace will keep burning */
    private int furnaceBurnTime;
    /** The number of ticks that a fresh copy of the currently-burning item would keep the furnace burning for */
    private int currentItemBurnTime;
    private int cookTime;
    private int totalCookTime;
    private final int COOK_TIME_FOR_COMPLETION = 200;

    /* The following are some IInventory methods you are required to override */

    /**
     * Furnace isBurning
     */
    public boolean isBurning()
    {
        return fractionOfCookTimeComplete() < 1;
    }

    @SideOnly(Side.CLIENT)
    public static boolean isBurning(IInventory inventory)
    {
        return inventory.getField(0) > 0 || inventory.getField(1) > 0;
    }

    // Gets the number of slots in the inventory
    @Override
    public int getSizeInventory() {
        return itemStacks.length;
    }

    // Gets the stack in the given slot
    @Override
    public ItemStack getStackInSlot(int slotIndex) {
        return itemStacks[slotIndex];
    }

    /**
     * Returns the amount of fuel remaining on the currently burning item in the given fuel slot.
     * @return fraction remaining, between 0 - 1
     */
    public double fractionOfFuelRemaining()
    {
        if (furnaceBurnTime <= 0 ) return 0;
        double fraction = currentItemBurnTime / (double)currentItemBurnTime;
        return MathHelper.clamp_double(fraction, 0.0, 1.0);
    }

    /**
     * return the remaining burn time of the fuel in the given slot
     * @return seconds remaining
     */
    public int secondsOfFuelRemaining()
    {
        if (currentItemBurnTime <= 0 ) return 0;
        return currentItemBurnTime / 20; // 20 ticks per second
    }

    /**
     * Returns the amount of cook time completed on the currently cooking item.
     * @return fraction remaining, between 0 - 1
     */
    public double fractionOfCookTimeComplete()
    {
        double fraction = cookTime / (double)COOK_TIME_FOR_COMPLETION;
        return MathHelper.clamp_double(fraction, 0.0, 1.0);
    }

    // This method is called every tick to update the tile entity, i.e.
    // - see if the fuel has run out, and if so turn the furnace "off" and slowly uncook the current item (if any)
    // - see if any of the items have finished smelting
    // It runs both on the server and the client.
    @Override
    public void update() {
        // If there is nothing to smelt or there is no room in the output, reset cookTime and return
        if (canSmelt()) {
            int numberOfFuelBurning = burnFuel();

            // If fuel is available, keep cooking the item, otherwise start "uncooking" it at double speed
            if (numberOfFuelBurning > 0) {
                cookTime += numberOfFuelBurning;
            }	else {
                cookTime -= 2;
            }

            if (cookTime < 0) cookTime = 0;

            // If cookTime has reached maxCookTime smelt the item and reset cookTime
            if (cookTime >= COOK_TIME_FOR_COMPLETION) {
                smeltItem();
                cookTime = 0;

            }
        }	else {
            cookTime = 0;
        }
    }

    /**
     * 	for each fuel slot: decreases the burn time, checks if burnTimeRemaining = 0 and tries to consume a new piece of fuel if one is available
     * @return the number of fuel slots which are burning
     */
    private int burnFuel() {
        int burningCount = 0;
        boolean inventoryChanged = false;
            if (currentItemBurnTime > 0) {
                --currentItemBurnTime;
                ++burningCount;
            }
            if (currentItemBurnTime == 0) {
                if (itemStacks[FUEL_SLOT] != null && getItemBurnTime(itemStacks[FUEL_SLOT]) > 0) {
                    // If the stack in this slot is not null and is fuel, set burnTimeRemaining & burnTimeInitialValue to the
                    // item's burn time and decrease the stack size
                    currentItemBurnTime = totalCookTime = getItemBurnTime(itemStacks[FUEL_SLOT]);
                    --itemStacks[FUEL_SLOT].stackSize;
                    ++burningCount;
                    inventoryChanged = true;
                    // If the stack size now equals 0 set the slot contents to the items container item. This is for fuel
                    // items such as lava buckets so that the bucket is not consumed. If the item dose not have
                    // a container item getContainerItem returns null which sets the slot contents to null
                    if (itemStacks[FUEL_SLOT].stackSize == 0) {
                        itemStacks[FUEL_SLOT] = itemStacks[FUEL_SLOT].getItem().getContainerItem(itemStacks[FUEL_SLOT]);
                    }
                }
            }
        if (inventoryChanged) markDirty();
        return burningCount;
    }

    /**
     * Check if any of the input items are smeltable and there is sufficient space in the output slots
     * @return true if smelting is possible
     */
    private boolean canSmelt() {return smeltItem(false);}

    /**
     * Smelt an input item into an output slot, if possible
     */
    private void smeltItem() {smeltItem(true);}

    /**
     * checks that there is an item to be smelted in one of the input slots and that there is room for the result in the output slots
     * If desired, performs the smelt
     * @param performSmelt if true, perform the smelt.  if false, check whether smelting is possible, but don't change the inventory
     * @return false if no items can be smelted, true otherwise
     */
    private boolean smeltItem(boolean performSmelt)
    {
        Integer firstSuitableInputSlot = null;
        Integer firstSuitableOutputSlot = null;
        ItemStack result = null;

        // finds the first input slot which is smeltable and whose result fits into an output slot (stacking if possible)
        for (int inputSlot = PROCESS_SLOT_1; inputSlot < PROCESS_SLOT_1 + 2; inputSlot++)	{
            if (itemStacks[inputSlot] != null) {
                result = getSmeltingResultForItem(itemStacks[inputSlot]);
                if (result != null) {
                    // find the first suitable output slot- either empty, or with identical item that has enough space
                    for (int outputSlot = OUTPUT_SLOT; outputSlot < OUTPUT_SLOT + 1; outputSlot++) {
                        ItemStack outputStack = itemStacks[outputSlot];
                        if (outputStack == null) {
                            firstSuitableInputSlot = inputSlot;
                            firstSuitableOutputSlot = outputSlot;
                            break;
                        }

                        if (outputStack.getItem() == result.getItem() && (!outputStack.getHasSubtypes() || outputStack.getMetadata() == outputStack.getMetadata())
                                && ItemStack.areItemStackTagsEqual(outputStack, result)) {
                            int combinedSize = itemStacks[outputSlot].stackSize + result.stackSize;
                            if (combinedSize <= getInventoryStackLimit() && combinedSize <= itemStacks[outputSlot].getMaxStackSize()) {
                                firstSuitableInputSlot = inputSlot;
                                firstSuitableOutputSlot = outputSlot;
                                break;
                            }
                        }
                    }
                    if (firstSuitableInputSlot != null) break;
                }
            }
        }

        if (firstSuitableInputSlot == null) return false;
        if (!performSmelt) return true;

        // alter input and output
        itemStacks[firstSuitableInputSlot].stackSize--;
        if (itemStacks[firstSuitableInputSlot].stackSize <=0) itemStacks[firstSuitableInputSlot] = null;
        if (itemStacks[firstSuitableOutputSlot] == null) {
            itemStacks[firstSuitableOutputSlot] = result.copy(); // Use deep .copy() to avoid altering the recipe
        } else {
            itemStacks[firstSuitableOutputSlot].stackSize += result.stackSize;
        }
        markDirty();
        return true;
    }

    // returns the smelting result for the given stack. Returns null if the given stack can not be smelted
    public static ItemStack getSmeltingResultForItem(ItemStack stack) { return FurnaceRecipes.instance().getSmeltingResult(stack); }

    // returns the number of ticks the given item will burn. Returns 0 if the given item is not a valid fuel
    public static short getItemBurnTime(ItemStack stack)
    {
        int burntime = TileEntityFurnace.getItemBurnTime(stack);  // just use the vanilla values
        return (short)MathHelper.clamp_int(burntime, 0, Short.MAX_VALUE);
    }

    /**
     * Removes some of the units from itemstack in the given slot, and returns as a separate itemstack
     * @param slotIndex the slot number to remove the items from
     * @param count the number of units to remove
     * @return a new itemstack containing the units removed from the slot
     */
    @Override
    public ItemStack decrStackSize(int slotIndex, int count) {
        ItemStack itemStackInSlot = getStackInSlot(slotIndex);
        if (itemStackInSlot == null) return null;

        ItemStack itemStackRemoved;
        if (itemStackInSlot.stackSize <= count) {
            itemStackRemoved = itemStackInSlot;
            setInventorySlotContents(slotIndex, null);
        } else {
            itemStackRemoved = itemStackInSlot.splitStack(count);
            if (itemStackInSlot.stackSize == 0) {
                setInventorySlotContents(slotIndex, null);
            }
        }
        markDirty();
        return itemStackRemoved;
    }

    // overwrites the stack in the given slotIndex with the given stack
    @Override
    public void setInventorySlotContents(int slotIndex, ItemStack itemstack) {
        itemStacks[slotIndex] = itemstack;
        if (itemstack != null && itemstack.stackSize > getInventoryStackLimit()) {
            itemstack.stackSize = getInventoryStackLimit();
        }
        markDirty();
    }

    // This is the maximum number if items allowed in each slot
    // This only affects things such as hoppers trying to insert items you need to use the container to enforce this for players
    // inserting items via the gui
    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    // Return true if the given player is able to use this block. In this case it checks that
    // 1) the world tileentity hasn't been replaced in the meantime, and
    // 2) the player isn't too far away from the centre of the block
    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        if (this.worldObj.getTileEntity(this.pos) != this) return false;
        final double X_CENTRE_OFFSET = 0.5;
        final double Y_CENTRE_OFFSET = 0.5;
        final double Z_CENTRE_OFFSET = 0.5;
        final double MAXIMUM_DISTANCE_SQ = 8.0 * 8.0;
        return player.getDistanceSq(pos.getX() + X_CENTRE_OFFSET, pos.getY() + Y_CENTRE_OFFSET, pos.getZ() + Z_CENTRE_OFFSET) < MAXIMUM_DISTANCE_SQ;
    }

    // Return true if the given stack is allowed to go in the given slot.  In this case, we can insert anything.
    // This only affects things such as hoppers trying to insert items you need to use the container to enforce this for players
    // inserting items via the gui
    @Override
    public boolean isItemValidForSlot(int slotIndex, ItemStack itemstack) {

        if (slotIndex != 3)
            return true;
        else
            return false;
    }

    // This is where you save any data that you don't want to lose when the tile entity unloads
    // In this case, it saves the itemstacks stored in the container
    @Override
    public void writeToNBT(NBTTagCompound parentNBTTagCompound)
    {
        super.writeToNBT(parentNBTTagCompound); // The super call is required to save and load the tileEntity's location

        // to use an analogy with Java, this code generates an array of hashmaps
        // The itemStack in each slot is converted to an NBTTagCompound, which is effectively a hashmap of key->value pairs such
        //   as slot=1, id=2353, count=1, etc
        // Each of these NBTTagCompound are then inserted into NBTTagList, which is similar to an array.
        NBTTagList dataForAllSlots = new NBTTagList();
        for (int i = 0; i < this.itemStacks.length; ++i) {
            if (this.itemStacks[i] != null)	{
                NBTTagCompound dataForThisSlot = new NBTTagCompound();
                dataForThisSlot.setByte("Slot", (byte) i);
                this.itemStacks[i].writeToNBT(dataForThisSlot);
                dataForAllSlots.appendTag(dataForThisSlot);
            }
        }
        // the array of hashmaps is then inserted into the parent hashmap for the container
        parentNBTTagCompound.setTag("Items", dataForAllSlots);
    }

    // This is where you load the data that you saved in writeToNBT
    @Override
    public void readFromNBT(NBTTagCompound parentNBTTagCompound)
    {
        super.readFromNBT(parentNBTTagCompound); // The super call is required to save and load the tiles location
        final byte NBT_TYPE_COMPOUND = 10;       // See NBTBase.createNewByType() for a listing
        NBTTagList dataForAllSlots = parentNBTTagCompound.getTagList("Items", NBT_TYPE_COMPOUND);

        Arrays.fill(itemStacks, null);           // set all slots to empty
        for (int i = 0; i < dataForAllSlots.tagCount(); ++i) {
            NBTTagCompound dataForOneSlot = dataForAllSlots.getCompoundTagAt(i);
            int slotIndex = dataForOneSlot.getByte("Slot") & 255;

            if (slotIndex >= 0 && slotIndex < this.itemStacks.length) {
                this.itemStacks[slotIndex] = ItemStack.loadItemStackFromNBT(dataForOneSlot);
            }
        }
    }


    // set all slots to empty
    @Override
    public void clear() {
        Arrays.fill(itemStacks, null);
    }

    @Override
    public boolean hasCustomName() {
        return false;
    }

    // will add a key for this container to the lang file so we can name it in the GUI
    @Override
    public String getName() {
        return "container.quantumprocessing:leadFurnace.name";
    }

    // standard code to look up what the human-readable name is
    @Override
    public IChatComponent getDisplayName() {
        return this.hasCustomName() ? new ChatComponentText(this.getName()) : new ChatComponentTranslation(this.getName());
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slotIndex) {
        ItemStack itemStack = getStackInSlot(slotIndex);
        if (itemStack != null) setInventorySlotContents(slotIndex, null);
        return itemStack;
    }

    @Override
    public void openInventory(EntityPlayer player) {}

    @Override
    public void closeInventory(EntityPlayer player) {}

    @Override
    public int getField(int id) {
        return 0;
    }

    @Override
    public void setField(int id, int value) {}

    @Override
    public int getFieldCount() {
        return 0;
    }
}
