/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package se.sics.gvod.common;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;
import se.sics.gvod.address.Address;
import se.sics.gvod.net.VodAddress;
import se.sics.gvod.net.Nat;

/**
 *
 * @author Owner
 */
public class SelfNoParents extends SelfBase
{
    /**
     * This is like a cached self-address.
     * When I need to use my real GVodAddress, i call
     * self.getAddress().
     * Only the parents of a GVodAddress may change, i can access
     * any other attribute of GVodAddress using the cached object.
     * @param addr
     */
    public SelfNoParents(VodAddress addr) {
        this(addr.getNat(), addr.getIp(), addr.getPort(), addr.getId(), addr.getOverlayId());
    }
    
    public SelfNoParents(Nat nat, InetAddress ip, int port, int nodeId, int overlayId) {
        super(nat, ip, port, nodeId, overlayId);
    }
    
    @Override
    public Utility getUtility() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateUtility(Utility utility) {
        throw new UnsupportedOperationException();
    }

    @Override
    public VodDescriptor getDescriptor() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Address> getParents() {
        return new HashSet<Address>();
    }
    
    @Override
    public boolean removeParent(Address parent) {
        return true;
    }

    @Override
    public void addParent(Address parent) {
        return;
    }

    @Override
    public Self clone(int overlayId)  {
        return new SelfNoParents(this.getNat(), getIp(), getPort(), nodeId, overlayId);
    }    

}