FROM python:3.12-slim
WORKDIR /app
COPY google_bridge/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY google_bridge /app/google_bridge
RUN useradd --uid 10001 --create-home bridge && mkdir /data && chown bridge:bridge /data
USER bridge
ENV BRIDGE_BIND=0.0.0.0 BRIDGE_DATABASE=/data/bridge.sqlite3 PORT=8080
EXPOSE 8080
CMD ["python", "-m", "google_bridge", "serve"]
