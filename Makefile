HOST := 46.101.153.18
SSH_KEY := ~/.ssh/digital_ocean
TOKEN := $(AI_MEMORY_TOKEN)

grafana:
	open http://$(HOST):3000

app:
	open http://$(HOST):8080

prometheus:
	ssh -f -N -L 9090:localhost:9090 -i $(SSH_KEY) root@$(HOST)
	open http://localhost:9090

prometheus-close:
	lsof -ti :9090 | xargs kill 2>/dev/null || true
