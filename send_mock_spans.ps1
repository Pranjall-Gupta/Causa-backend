# Powershell Utility to send mock spans and metrics to CAUSA Backend

$baseUrl = "http://localhost:5000/v1"
$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$nowNano = $now * 1000000

# Helper to generate unique hex ID
function New-HexId($length) {
    $chars = "0123456789abcdef"
    $id = ""
    for ($i = 0; $i -lt $length; $i++) {
        $id += $chars[(Get-Random -Maximum 16)]
    }
    return $id
}

Write-Host "Sending normal telemetry to initialize topology..."

# Send 5 normal requests (checkout-api -> order-service -> inventory-service)
for ($i = 1; $i -le 5; $i++) {
    $traceId = New-HexId 32
    $checkoutSpanId = New-HexId 16
    $orderSpanId = New-HexId 16
    $inventorySpanId = New-HexId 16
    
    $start = $nowNano - ($i * 1000 * 1000000)
    
    # 1. Checkout API span
    $checkoutSpan = @{
        traceId = $traceId
        spanId = $checkoutSpanId
        parentSpanId = $null
        name = "HTTP GET /checkout"
        kind = "SPAN_KIND_SERVER"
        startTimeUnixNano = $start.ToString()
        endTimeUnixNano = ($start + 80 * 1000000).ToString() # 80ms
        status = @{ code = 1 } # OK
    }
    
    # 2. Order Service span
    $orderSpan = @{
        traceId = $traceId
        spanId = $orderSpanId
        parentSpanId = $checkoutSpanId
        name = "HTTP POST /orders"
        kind = "SPAN_KIND_SERVER"
        startTimeUnixNano = ($start + 10 * 1000000).ToString()
        endTimeUnixNano = ($start + 70 * 1000000).ToString() # 60ms
        status = @{ code = 1 } # OK
    }
    
    # 3. Inventory Service span
    $inventorySpan = @{
        traceId = $traceId
        spanId = $inventorySpanId
        parentSpanId = $orderSpanId
        name = "DB SELECT inventory"
        kind = "SPAN_KIND_CLIENT"
        startTimeUnixNano = ($start + 20 * 1000000).ToString()
        endTimeUnixNano = ($start + 50 * 1000000).ToString() # 30ms
        status = @{ code = 1 } # OK
    }
    
    # Pack as OTLP JSON for checkout-api
    $bodyCheckout = @{
        resourceSpans = @(
            @{
                resource = @{ attributes = @( @{ key = "service.name"; value = @{ stringValue = "checkout-api" } } ) }
                scopeSpans = @( @{ spans = @( $checkoutSpan ) } )
            }
        )
    }
    
    # Pack as OTLP JSON for order-service
    $bodyOrder = @{
        resourceSpans = @(
            @{
                resource = @{ attributes = @( @{ key = "service.name"; value = @{ stringValue = "order-service" } } ) }
                scopeSpans = @( @{ spans = @( $orderSpan ) } )
            }
        )
    }

    # Pack as OTLP JSON for inventory-service
    $bodyInventory = @{
        resourceSpans = @(
            @{
                resource = @{ attributes = @( @{ key = "service.name"; value = @{ stringValue = "inventory-service" } } ) }
                scopeSpans = @( @{ spans = @( $inventorySpan ) } )
            }
        )
    }

    Invoke-RestMethod -Uri "$baseUrl/traces" -Method Post -Body (ConvertTo-Json -InputObject $bodyCheckout -Depth 15) -ContentType "application/json"
    Invoke-RestMethod -Uri "$baseUrl/traces" -Method Post -Body (ConvertTo-Json -InputObject $bodyOrder -Depth 15) -ContentType "application/json"
    Invoke-RestMethod -Uri "$baseUrl/traces" -Method Post -Body (ConvertTo-Json -InputObject $bodyInventory -Depth 15) -ContentType "application/json"
}

Write-Host "Sending cascading failure telemetry..."
Write-Host "Scenario: payment-service latency/errors -> order-service latency -> checkout-api error rate spikes"

# Send 5 slow/failing cascading requests (checkout-api -> order-service -> payment-service)
# This will trigger:
# 1. Payment service: high latency (2.1s) and errors -> alert-payment-service-high-error-rate + latency
# 2. Order service: high latency (1.8s) -> alert-order-service-latency-high
# 3. Checkout API: errors (60% rate) -> alert-checkout-api-high-error-rate
for ($i = 1; $i -le 5; $i++) {
    $traceId = New-HexId 32
    $checkoutSpanId = New-HexId 16
    $orderSpanId = New-HexId 16
    $paymentSpanId = New-HexId 16
    
    $start = $nowNano
    
    # Payment-service throws error in 3 of the 5 requests
    $isPaymentError = ($i -le 3)
    $paymentStatus = if ($isPaymentError) { @{ code = 2; message = "Payment Gateway Timeout" } } else { @{ code = 1 } }
    
    # Checkout API throws error in 4 of the 5 requests
    $isCheckoutError = ($i -le 4)
    $checkoutStatus = if ($isCheckoutError) { @{ code = 2; message = "Internal Server Error" } } else { @{ code = 1 } }
    
    # 1. Checkout API (Duration: 2.2 seconds)
    $checkoutSpan = @{
        traceId = $traceId
        spanId = $checkoutSpanId
        parentSpanId = $null
        name = "HTTP GET /checkout"
        kind = "SPAN_KIND_SERVER"
        startTimeUnixNano = $start.ToString()
        endTimeUnixNano = ($start + 2200 * 1000000).ToString() # 2200ms (High latency + Error)
        status = $checkoutStatus
    }
    
    # 2. Order Service (Duration: 1.8 seconds)
    $orderSpan = @{
        traceId = $traceId
        spanId = $orderSpanId
        parentSpanId = $checkoutSpanId
        name = "HTTP POST /orders"
        kind = "SPAN_KIND_SERVER"
        startTimeUnixNano = ($start + 100 * 1000000).ToString()
        endTimeUnixNano = ($start + 1900 * 1000000).ToString() # 1800ms (Latency warning)
        status = @{ code = 1 } # OK but slow
    }
    
    # 3. Payment Service (Duration: 1.6 seconds)
    $paymentSpan = @{
        traceId = $traceId
        spanId = $paymentSpanId
        parentSpanId = $orderSpanId
        name = "HTTP POST /payments"
        kind = "SPAN_KIND_SERVER"
        startTimeUnixNano = ($start + 200 * 1000000).ToString()
        endTimeUnixNano = ($start + 1800 * 1000000).ToString() # 1600ms (Latency critical + Error)
        status = $paymentStatus
    }
    
    # Pack OTLP JSONs
    $bodyCheckout = @{
        resourceSpans = @(
            @{
                resource = @{ attributes = @( @{ key = "service.name"; value = @{ stringValue = "checkout-api" } } ) }
                scopeSpans = @( @{ spans = @( $checkoutSpan ) } )
            }
        )
    }
    
    $bodyOrder = @{
        resourceSpans = @(
            @{
                resource = @{ attributes = @( @{ key = "service.name"; value = @{ stringValue = "order-service" } } ) }
                scopeSpans = @( @{ spans = @( $orderSpan ) } )
            }
        )
    }

    $bodyPayment = @{
        resourceSpans = @(
            @{
                resource = @{ attributes = @( @{ key = "service.name"; value = @{ stringValue = "payment-service" } } ) }
                scopeSpans = @( @{ spans = @( $paymentSpan ) } )
            }
        )
    }

    Invoke-RestMethod -Uri "$baseUrl/traces" -Method Post -Body (ConvertTo-Json -InputObject $bodyCheckout -Depth 15) -ContentType "application/json"
    Invoke-RestMethod -Uri "$baseUrl/traces" -Method Post -Body (ConvertTo-Json -InputObject $bodyOrder -Depth 15) -ContentType "application/json"
    Invoke-RestMethod -Uri "$baseUrl/traces" -Method Post -Body (ConvertTo-Json -InputObject $bodyPayment -Depth 15) -ContentType "application/json"
}

Write-Host "Sending CPU and Memory usage metrics..."
$metrics = @(
    @{ serviceName = "checkout-api"; metricName = "cpu_usage"; value = 85.0 },
    @{ serviceName = "checkout-api"; metricName = "memory_usage"; value = 512.0 },
    
    @{ serviceName = "order-service"; metricName = "cpu_usage"; value = 72.0 },
    @{ serviceName = "order-service"; metricName = "memory_usage"; value = 380.0 },
    
    @{ serviceName = "payment-service"; metricName = "cpu_usage"; value = 95.0 },
    @{ serviceName = "payment-service"; metricName = "memory_usage"; value = 256.0 },
    
    @{ serviceName = "inventory-service"; metricName = "cpu_usage"; value = 42.0 },
    @{ serviceName = "inventory-service"; metricName = "memory_usage"; value = 768.0 }
)

Invoke-RestMethod -Uri "$baseUrl/metrics" -Method Post -Body (ConvertTo-Json -InputObject $metrics) -ContentType "application/json"

Write-Host "Telemetry setup completed successfully! Open your browser and view CAUSA."
