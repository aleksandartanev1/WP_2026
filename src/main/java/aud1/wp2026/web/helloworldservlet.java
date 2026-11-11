package aud1.wp2026.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.
        io.IOException;
import java.io.PrintWriter;

@WebServlet(name = "pump", urlPatterns = "/hello")
public class helloworldservlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("text/html");
        PrintWriter writer = resp.getWriter();
        writer.write("<html><head></head><body><h1>esketit</h1></body></html>");
        writer.flush();
    }
}