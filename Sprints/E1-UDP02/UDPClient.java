import java.net.*;
import java.io.*;
import java.util.Scanner;

public class UDPClient {

    public static void main(String args[]) {
        DatagramSocket aSocket = null;
        Scanner scanner = new Scanner(System.in); // Para ler do teclado

        try {
            aSocket = new DatagramSocket();
            InetAddress aHost = InetAddress.getByName("localhost");
            int serverPort = 6789;

            System.out.println("Cliente UDP iniciado.");
            System.out.println("Escreva a mensagem no formato N,texto (ou 'sair' para fechar).");

            // Ciclo para enviar várias mensagens seguidas
            while (true) {
                System.out.print("\nMensagem a enviar: ");
                String textoParaEnviar = scanner.nextLine();

                if (textoParaEnviar.equalsIgnoreCase("sair")) {
                    break;
                }

                // 1. Prepara e envia a mensagem
                byte[] m = textoParaEnviar.getBytes();
                DatagramPacket request = new DatagramPacket(m, m.length, aHost, serverPort);
                aSocket.send(request);

                // 2. Fica à espera da resposta do servidor
                byte[] bufferBytes = new byte[1000];
                DatagramPacket reply = new DatagramPacket(bufferBytes, bufferBytes.length);
                aSocket.receive(reply);

                // 3. Imprime a resposta
                String resposta = new String(reply.getData(), 0, reply.getLength());
                System.out.println("Resposta do Servidor: " + resposta);
            }

        } catch (SocketException e) {
            System.out.println("Socket: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IO: " + e.getMessage());
        } finally {
            if (aSocket != null) aSocket.close();
            scanner.close();
        }
    }
}