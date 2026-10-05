"""The observed Large V3 Turbo VoiceAI tensor contract; no vendor implementation."""

SDK_VERSION = "2.50.0.260828"
FLOAT = "QNN_DATATYPE_FLOAT_16"
INT = "QNN_DATATYPE_INT_32"


def tensor(name, shape, dtype=FLOAT):
    return {"info": {"name": name, "dimensions": shape, "dataType": dtype}}


def reference(component):
    cross = [tensor(f"{kind}_cache_cross_{i}", shape)
             for i in range(4) for kind, shape in (
                 ("k", [20, 1, 64, 1500]), ("v", [20, 1, 1500, 64]))]
    if component == "encoder":
        inputs = [tensor("input_features", [1, 128, 3000])]
        outputs = cross
    elif component == "decoder":
        inputs = [tensor("input_ids", [1, 1], INT),
                  tensor("attention_mask", [1, 1, 1, 200])]
        for i in range(4):
            inputs.extend([tensor(f"k_cache_self_{i}_in", [20, 1, 64, 199]),
                           tensor(f"v_cache_self_{i}_in", [20, 1, 199, 64])])
        inputs.extend(cross)
        inputs.append(tensor("position_ids", [1], INT))
        outputs = [tensor("logits", [1, 51866, 1, 1])]
        for i in range(4):
            outputs.extend([tensor(f"k_cache_self_{i}_out", [20, 1, 64, 199]),
                            tensor(f"v_cache_self_{i}_out", [20, 1, 199, 64])])
    else:
        raise ValueError(f"Unknown component: {component}")
    return {"graphName": f"hf_whisper_{component}",
            "graphInputs": inputs, "graphOutputs": outputs}


def tensor_map(tensors):
    result = {}
    for item in tensors:
        info = item["info"]
        if info["name"] in result:
            raise ValueError(f"Duplicate tensor: {info['name']}")
        result[info["name"]] = {"shape": info["dimensions"], "dtype": info["dataType"]}
    return result


def validate_context(document, component, soc_model, dsp_arch):
    """Require the exact target AND the complete runtime interface. No HTP claim."""
    body = document["info"]
    if body["socModel"] != soc_model:
        raise ValueError(f"Wrong socModel: {body['socModel']} != {soc_model}")
    actual_arch = body["contextMetadata"]["info"]["dspArch"]
    if actual_arch != dsp_arch:
        raise ValueError(f"Wrong dspArch: {actual_arch} != {dsp_arch}")
    if body.get("backendId") != 6:
        raise ValueError("Expected HTP backend 6")
    if not body.get("buildId", "").startswith("v" + SDK_VERSION):
        raise ValueError("Expected QAIRT 2.50.0.260828 context build")
    if len(body["graphs"]) != 1:
        raise ValueError("Expected exactly one graph")
    graph = body["graphs"][0]["info"]
    expected = reference(component)
    if graph["graphName"] != expected["graphName"]:
        raise ValueError(f"Wrong graph: {graph['graphName']}")
    for direction in ("graphInputs", "graphOutputs"):
        if tensor_map(graph[direction]) != tensor_map(expected[direction]):
            raise ValueError(f"Wrong {component} {direction} names, shapes or dtypes")
    return {"socModel": soc_model, "dspArch": dsp_arch, "backendId": 6,
            "buildId": body["buildId"], "graphName": graph["graphName"],
            "inputs": tensor_map(graph["graphInputs"]),
            "outputs": tensor_map(graph["graphOutputs"])}
