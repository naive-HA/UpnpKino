import time
import socket
import select
import requests

if __name__ == '__main__':
    SSDP_GROUP = ("239.255.255.250", 1900)
    TIMEOUT = 10.0

    def postPayload(server_address, control_url, action, object_id):
        data = {"ObjectID"      : object_id, 
                "BrowseFlag"    : "BrowseDirectChildren",
                "Filter"        : "*",
                "StartingIndex" : 0,
                "RequestedCount": 5000,
                "SortCriteria"  : ""}
        fields = str()
        for tag, value in data.items():
            fields += '<{tag}>{value}</{tag}>'.format(tag=tag, value=value)
        payload = '<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:{action} xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">{fields}</u:{action}></s:Body></s:Envelope>'.format(action=action, fields=fields)      
        packet = "\r\n".join([
            'POST {} HTTP/1.1'.format(control_url),
            'User-Agent: UPnP/1.0',
            'Accept: */*',
            'Content-Type: text/xml; charset="utf-8"',
            'HOST: {}'.format(server_address),
            'Content-Length: {}'.format(len(payload)),
            'SOAPACTION: "urn:schemas-upnp-org:service:ContentDirectory:1#{}"'.format(action),
            'Connection: close',
            '',
            payload,
            ])
        return packet

    def sendPostRequest(server_address, packet):
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(5)
            sock.connect((server_address.split("//")[1].split(":")[0], int(server_address.split("//")[1].split(":")[1])))
            sock.sendall(packet.encode('utf-8'))
            data = sock.recv(5*2048)
            data = data.decode('utf-8')
        except Exception as e:
            print(e)
            data = str()
        finally:
            sock.close()
        return data

    payload = "\r\n".join([
                'M-SEARCH * HTTP/1.1',
                'HOST: {}:{}'.format(*SSDP_GROUP),
                'ST: ssdp:all',
                'MAN: "ssdp:discover"',
                'MX: 5',
                '',
                ''])
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.sendto(payload.encode(), SSDP_GROUP)
    start = time.time()
    while True:
        if time.time() - start > TIMEOUT:
            break # timed out
        read, write, error = select.select([sock], [], [sock], 1)
        if sock in read:
            data, addr = sock.recvfrom(1024)
            print(data.decode())
            response = data.decode().split("\r\n")
            for line in response:
                _line = line.split(" ")
                if _line[0] == "LOCATION:":
                    service_description = _line[1][0:]
                    server_address = "http://" + service_description.split("/")[2]
            break
        elif sock in error:
            break
        else:
            pass # Nothing to read
    sock.close()
    print("Server address: {}".format(server_address))
    with requests.get(url=service_description, stream=True) as r:
        r.raise_for_status()
        for chunk in r.iter_content(chunk_size=8192):
            server_description = chunk.decode()
    controlURL = server_description.split("<controlURL>")[1].split("</controlURL>")[0]
    print("Server control url: {}{}".format(server_address, controlURL))
    packet = postPayload(server_address= server_address, 
                         control_url=    controlURL, 
                         action=         "Browse", 
                         object_id=      0)
    postResponse = sendPostRequest(server_address=server_address, packet=packet)
    print(postResponse)