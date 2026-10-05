"""No SDK or weights needed: reject plausible but unsafe context metadata."""
import copy
import unittest

from whisper_contract import reference, validate_context


class ContractTest(unittest.TestCase):
    def fixture(self, soc=97, arch=81):
        return {"info": {"socModel": soc, "contextMetadata": {"info": {"dspArch": arch}},
                         "backendId": 6, "buildId": "v2.50.0.260828221209",
                         "graphs": [{"info": reference("decoder")}]}}

    def test_both_known_target_pairs(self):
        for soc, arch in ((57, 75), (97, 81)):
            self.assertEqual(soc, validate_context(self.fixture(soc, arch), "decoder", soc, arch)["socModel"])

    def test_same_htp_different_soc_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "socModel"):
            validate_context(self.fixture(87, 81), "decoder", 97, 81)

    def test_wrong_arch_backend_runtime_and_graph_are_rejected(self):
        for mutate in (
                lambda b: b["contextMetadata"]["info"].update(dspArch=75),
                lambda b: b.update(backendId=3),
                lambda b: b.update(buildId="v2.49.0"),
                lambda b: b["graphs"][0]["info"].update(graphName="other")):
            document = self.fixture()
            mutate(document["info"])
            with self.assertRaises(ValueError):
                validate_context(document, "decoder", 97, 81)

    def test_missing_duplicate_misshaped_and_wrong_dtype_inputs_rejected(self):
        for mutation in ("missing", "duplicate", "shape", "dtype"):
            document = self.fixture()
            tensors = document["info"]["graphs"][0]["info"]["graphInputs"]
            if mutation == "missing":
                tensors.pop()
            elif mutation == "duplicate":
                tensors.append(copy.deepcopy(tensors[0]))
            elif mutation == "shape":
                tensors[0]["info"]["dimensions"] = [1, 2]
            else:
                tensors[0]["info"]["dataType"] = "QNN_DATATYPE_FLOAT_16"
            with self.assertRaises(ValueError):
                validate_context(document, "decoder", 97, 81)


if __name__ == "__main__":
    unittest.main()
