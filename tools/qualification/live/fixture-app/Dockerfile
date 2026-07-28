FROM python:3.12-alpine
WORKDIR /app
COPY server.py /app/server.py
USER 65534:65534
EXPOSE 8080
ENTRYPOINT ["python3", "/app/server.py"]
