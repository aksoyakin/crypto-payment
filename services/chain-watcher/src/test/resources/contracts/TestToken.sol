// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/// @notice Only what the watcher's tests need from a token: a supply the deployer holds, and transfers that
/// emit the standard ERC-20 event the watcher reads.
contract TestToken {
    event Transfer(address indexed from, address indexed to, uint256 value);

    string public constant name = "Test USD Coin";
    string public constant symbol = "TUSDC";
    uint8 public constant decimals = 6;

    mapping(address => uint256) public balanceOf;

    constructor(uint256 supply) {
        balanceOf[msg.sender] = supply;
        emit Transfer(address(0), msg.sender, supply);
    }

    function transfer(address to, uint256 value) external returns (bool) {
        require(balanceOf[msg.sender] >= value, "insufficient balance");
        balanceOf[msg.sender] -= value;
        balanceOf[to] += value;
        emit Transfer(msg.sender, to, value);
        return true;
    }
}
