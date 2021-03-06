/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package se.sics.kompics.nat.utils.getip.events;

import java.net.InetAddress;
import java.util.Iterator;
import java.util.List;
import se.sics.kompics.Response;
import se.sics.kompics.nat.utils.getip.IpAddrStatus;

/**
 *
 * @author jdowling
 */
public class GetIpResponse extends Response {

    private final List<IpAddrStatus> addrs;
    private final InetAddress boundIp;

    public GetIpResponse(GetIpRequest request, List<IpAddrStatus> addrs,
            InetAddress boundIp) {
        super(request);
        if (addrs == null) {
            throw new IllegalArgumentException("List of IpAddrStatus cannot be null");
        }
        this.addrs = addrs;
        this.boundIp = boundIp;
    }

    public InetAddress getBoundIp() {
        return boundIp;
    }

    public List<IpAddrStatus> getAddrs() {
        return addrs;
    }

    public boolean hasIpAddrStatus() {
        return !addrs.isEmpty();
    }

    public IpAddrStatus getFirstAddress() {
        return addrs.get(0);
    }

    public InetAddress getIpAddress() {
        if (hasIpAddrStatus()) {
            return getFirstAddress().getAddr();
        } else {
            return getBoundIp();
        }
    }

    /**
     * 
     * @param number the network interface number, starting at 1.
     * @return 
     */
    public InetAddress getTenDotIpAddress(int number) {
        InetAddress addr = null;
        Iterator<IpAddrStatus> iter = addrs.iterator();
        int i=0;
        while (iter.hasNext() && i < number) {
            addr = iter.next().getAddr();
            String textualPrefixAddr = addr.getHostAddress();
            int firstDot = textualPrefixAddr.indexOf(".");
            if (firstDot > 0) {
                textualPrefixAddr = textualPrefixAddr.substring(0, firstDot);
            }
            if (textualPrefixAddr.compareTo("10") == 0) {
                i++;
            } else {
                addr = null;
            }
        }
        return addr;
    }
}
