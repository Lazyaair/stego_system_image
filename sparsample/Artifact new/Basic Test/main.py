from typing import Tuple, List, Dict, Any
import torch
from transformers import PreTrainedModel, PreTrainedTokenizer
from utils import load_model, read_binary_message
from sparsamp import encode_spar, decode_spar
from dataclasses import dataclass
from transformers import AutoTokenizer, AutoModelForCausalLM
import time


@dataclass
class SparsampConfig:
    message_file: str
    message_length: int
    block_size: int
    context: str
    model_path: str
    random_seed: int
    token_num_need_generated: int
    top_p: float = 1.0

def save_stega_text(text: str, filename: str = "stega_text.txt") -> str:
    """Save steganographic text to file and return the saved file path"""
    with open(filename, 'w', encoding='utf-8') as f:
        f.write(text)
    return filename

def load_stega_text(filename: str) -> str:
    """Read steganographic text from file"""
    with open(filename, 'r', encoding='utf-8') as f:
        return f.read()

def verify_token_ids(original_ids: torch.Tensor, regenerated_ids: torch.Tensor) -> bool:
    """Verify if original and regenerated token IDs match"""
    return torch.equal(torch.tensor(original_ids), regenerated_ids[0])

def process_message(message: str, block_size: int) -> str:
    """Process message to ensure length meets requirements"""
    if len(message) % block_size != 0:
        padding_length = block_size - (len(message) % block_size)
        message = message + '0' * padding_length
        print(f"Message length padded to {len(message)} bits")
    return message

def test_sparsamp(config: SparsampConfig, device: torch.device) -> Dict[str, Any]:
    """
    Execute Sparsamp test process
    Returns test results dictionary
    """
    print("\n=== Initialization Phase ===")
    message = read_binary_message(config.message_file)
    message = message[:config.message_length]
    message = process_message(message, config.block_size)
    print(f"Number of message bits used: {len(message)}")
    
    try:
        model, tokenizer = load_model(config.model_path, device)
    except:
        print("Failed to load model locally, load GPT-2 directly from Transformers library")
        tokenizer = AutoTokenizer.from_pretrained("openai-community/gpt2")
        model = AutoModelForCausalLM.from_pretrained("openai-community/gpt2")
        print("Model loaded successfully")
        model.to(device)
        model.eval()

    
    context = tokenizer.encode(config.context, return_tensors='pt').to(device)

    print("\n=== Encoding Phase ===")
    encode_start_time = time.time()
    generated_ids, encoded_message, entropy, stat_time, model_time = encode_spar(
        model=model,
        context=context,
        message_bits=message,
        token_num_need_generation=config.token_num_need_generated,
        device=device,
        block_size=config.block_size,
        top_p=config.top_p,
        random_seed=config.random_seed
    )
    encode_time = time.time() - encode_start_time

    stega_text = tokenizer.decode(generated_ids)
    print(f"Generated steganographic text: {stega_text}")
    
    print("\n=== Save and Verification Phase ===")
    stega_file = save_stega_text(stega_text)
    print(f"Steganographic text saved to: {stega_file}")
    
    loaded_stega_text = load_stega_text(stega_file)
    regenerated_ids = tokenizer.encode(loaded_stega_text, return_tensors='pt')
    
    is_same = verify_token_ids(generated_ids, regenerated_ids)
    print(f"Whether there is Token Ambiguity: {'No' if is_same else 'Yes'}")
    # We decode messages from sentences that without Token Ambiguity.
    print("\n=== Decoding Phase ===")
    decode_start_time = time.time()
    reconstructed_message = decode_spar(
        model=model,
        generated_ids=generated_ids,# We decode messages from sentences that without Token Ambiguity.
        context=context,
        device=device,
        block_size=config.block_size,
        top_p=config.top_p,
        random_seed=config.random_seed
    )
    decode_time = time.time() - decode_start_time

    is_successful = encoded_message == reconstructed_message
    token_num_generated = len(generated_ids)
    encoded_message_bits = len(encoded_message) * config.block_size
    utilization = (encoded_message_bits / entropy * 100) if entropy != 0 else 0
    Embedding_Speed = encoded_message_bits / (encode_time - stat_time)
    Decoding_Speed = encoded_message_bits / decode_time
    ATST = encode_time / token_num_generated
    Generation_Speed = token_num_generated / encode_time
    SITR = (encode_time - model_time - stat_time) / encode_time
    Embedding_Rate = encoded_message_bits / token_num_generated


    results = {
        'success': is_successful,
        'token_num_generated': token_num_generated,
        'encoded_message_bits': encoded_message_bits,
        'utilization': utilization,
        'Embedding_Speed': Embedding_Speed,
        'Decoding_Speed': Decoding_Speed,
        'ATST': ATST,
        'Generation_Speed': Generation_Speed,
        'SITR': SITR,
        'Embedding_Rate': Embedding_Rate
    }

    print("\n=== Evaluation Results ===")
    print(f"Experimental Settings - Model: {config.model_path}, Top-p: {config.top_p}, Message Segment Length (l_m): {config.block_size}")
    print(f"Embedded {encoded_message_bits} bits message in the generated {token_num_generated} tokens")
    print(f"Message decoding result: {'Success' if is_successful else 'Failed'}")
    print(f"ATST: {ATST:.2e} s/token")
    print(f"SITR: {SITR:.2f}")
    print(f"Generation Speed: {Generation_Speed:.1f} tokens/s")
    print(f"Embedding Rate: {Embedding_Rate:.2f} bits/token")
    print(f"Utilization: {utilization:.1f}%")
    print(f"Embedding Speed: {Embedding_Speed:.1f} bits/s")
    print(f"Decoding Speed: {Decoding_Speed:.1f} bits/s")


    return results

if __name__ == '__main__':
    config = SparsampConfig(
        message_file="./message_bits.txt",
        message_length=12800,
        block_size=64,
        context="Give me a short introduction to large language model.",
        model_path="../sparsamp_test/gpt/",
        random_seed=32,
        token_num_need_generated=100,
        top_p=0.95
    )

    device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
    print(f"\nUsing device: {device}")

    test_results = test_sparsamp(config, device)