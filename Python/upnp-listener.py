import socket
import struct

SSDP_GROUP = ("239.255.255.250", 1900)

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind((SSDP_GROUP[0], SSDP_GROUP[1]))
mreq = struct.pack("4sl", socket.inet_aton(SSDP_GROUP[0]), socket.INADDR_ANY)

sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)

while True:
    print(sock.recv(10240))
