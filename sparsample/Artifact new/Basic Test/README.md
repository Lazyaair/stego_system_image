# README

## Overview

This repository contains a Python implementation for encoding and decoding messages using SparSamp in a neural network model. The core functionalities are encapsulated in the `encode_spar` and `decode_spar` functions, which leverage probabilistic token generation based on a given context.

## Requirements

- python 3.8
- pytorch 2.2.2
- transformers: 4.41.2
- scipy 
- Additional dependencies may be required based on your environment.

## Usage

### Functions

#### 1. `encode_spar`

Encodes a message into a sparse representation using a provided model.

- **Parameters:**
  - `model`: The neural network model for generating probabilities.
  - `context`: The initial context for the model, which should be obtained by calling `tokenizer.encode()` on your input text.
  - `message_bits`: The message to be embedded, represented as a binary string.
  - `token_num_need_generation`: The number of tokens to generate.
  - `device`: The device to run the model on (default: 'cuda').
  - `block_size`: Size of each block in bits (default: 32).
  - `top_p`: Top-p sampling parameter (default: 1.0).
  - `random_seed`: Seed for random number generation (default: 42).
- **Returns:**
  - `generated_ids`: List of generated token IDs.
  - `encoded_message`: The encoded message.
  - `total_entropy`: Total entropy calculated during the encoding process.
  - `stat_time`: Total time spent on statistics.
  - `model_time`: Total time spent on model predictions.

#### 2. `decode_spar`

Decodes the sparse representation back into the original message.

- **Parameters:**
  - `model`: The neural network model used during encoding.
  - `generated_ids`: The list of generated token IDs from the encoding step.
  - `context`: The initial context for the model, which should be obtained by calling `tokenizer.encode()` on your input text.
  - `device`: The device to run the model on (default: 'cuda').
  - `block_size`: Size of each block in bits (default: 32).
  - `top_p`: Top-p sampling parameter (default: 1.0).
  - `random_seed`: Seed for random number generation (default: 42).
- **Returns:**
  - `message`: The decoded message as a list of binary strings.

### Example

You can run `test_sparsamp()` in main.py as an example. Before you run it, make sure you have downloaded a pretrained language model and prepared message bits file. More details you can see in main.py.

## Notes

- Make sure your model and context are properly set up before using the encoding and decoding functions.
- Adjust `block_size` and `top_p` according to your specific requirements.
- The code uses random sampling; results may vary across runs if not controlled with `random_seed`.
- The recommended pre-trained models are GPT-2, Llama 3, and Qwen2.5.
- GPT-2:  https://huggingface.co/openai-community/gpt2
- Llama3: https://huggingface.co/meta-llama/Llama-3.1-8B-Instruct
- Qwen2.5: https://huggingface.co/Qwen/Qwen2.5-3B-Instruct